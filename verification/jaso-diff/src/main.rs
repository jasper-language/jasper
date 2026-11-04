//! 差分表生成器：种子固定，输出逐字节可复现。
//! 用法：`jaso-diff check-emit <目录>` 核对 Jasper 编译器写出的文件，见 `check_emit`；
//! `jaso-diff <向量目录> <输出目录>` 产出七张 TSV：
//! * `diff-files.tsv`：向量 + 字节变异（重算摘要，可重签）→ 一致性判定结果
//! * `diff-load.tsv`：完整文件（十六进制，清单随机）→ 一致性判定结果，专门走到信任之后的装载步骤
//! * `diff-targeted.tsv`：完整文件（十六进制），各规则边界两侧的定向用例 → 一致性判定结果
//! * `diff-platforms.tsv`：完整文件（十六进制）→ 判定结果与平台不符的字段、宿主值、文件值
//! * `diff-manifests.tsv`：清单文本（十六进制）→ 错误码或规范化转储
//! * `diff-signatures.tsv`：公钥、消息、签名 → 公钥合法性与严格验签结果
//! * `diff-bindings.tsv`：清单文本、绑定内容（都是十六进制）→ L1 第 6a 步的结果

use curve25519_dalek::{EdwardsPoint, Scalar};
use ed25519_dalek::{Signature, VerifyingKey};
use jaso::build::{Builder, PlatformIn, SegmentIn};
use jaso::conformance::{check, expect_line};
use jaso::manifest::Manifest;
use jaso::pack::development_signer;
use jaso::{PlatformField, Target, digest_of, signed_digest_of, signed_message, Arch, FloatAbi, Jaso, ModuleKind, Stage, FLAG_R, FLAG_X};
use sha2::{Digest, Sha512};
use std::fmt::Write as _;
use std::path::Path;
use std::process::ExitCode;

/// SplitMix64：标准、短、跨平台确定。
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E3779B97F4A7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
        z ^ (z >> 31)
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
    fn pick<'a>(&mut self, xs: &[&'a str]) -> &'a str {
        xs[self.below(xs.len())]
    }
}

fn hex(b: &[u8]) -> String {
    let mut s = String::with_capacity(b.len() * 2);
    for c in b {
        write!(s, "{c:02x}").unwrap();
    }
    s
}

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len() / 2).map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap()).collect()
}

fn reseal(b: &mut [u8]) {
    let d = digest_of(b);
    b[40..72].copy_from_slice(&d);
}

fn u32_at(b: &[u8], o: usize) -> usize {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]]) as usize
}

/// 字节变异：头部、目录、任意位置三类落点，取值偏向边界值。
fn files(dir: &Path, key: [u8; 32], rng: &mut Rng, cases: usize) -> String {
    let signer = development_signer();
    let mut names: Vec<String> = std::fs::read_dir(dir)
        .unwrap()
        .flatten()
        .filter_map(|e| e.file_name().to_str().map(String::from))
        .filter(|n| n.ends_with(".jaso"))
        .collect();
    names.sort();
    let originals: Vec<Vec<u8>> = names.iter().map(|n| std::fs::read(dir.join(n)).unwrap()).collect();
    let mut out = String::new();
    for _ in 0..cases {
        // 合法向量权重更高：非法向量多半第一处违例就停，到不了后面的检查
        let i = if rng.below(4) < 3 {
            let oks: Vec<usize> = (0..names.len()).filter(|&k| names[k].starts_with("ok-")).collect();
            oks[rng.below(oks.len())]
        } else {
            rng.below(names.len())
        };
        let mut b = originals[i].clone();
        let signed_at = Jaso::parse(&b).ok().and_then(|f| f.signature()).map(|s| s.file_offset as usize);
        let mut writes = Vec::new();
        for _ in 0..1 + rng.below(3) {
            let dir_off = u32_at(&b, 24).min(b.len());
            let dir_len = u32_at(&b, 20).saturating_mul(32).min(b.len() - dir_off).max(1);
            let off = match rng.below(10) {
                0..=1 => rng.below(80.min(b.len())),
                2..=5 => dir_off + rng.below(dir_len),
                _ => rng.below(b.len()),
            };
            if off >= b.len() || (40..72).contains(&off) {
                continue;
            }
            let v = match rng.below(6) {
                0 => 0u8,
                1 => 1,
                2 => 0xff,
                3 => b[off] ^ (1 << rng.below(8)),
                4 => b[off].wrapping_add(1),
                _ => rng.next() as u8,
            };
            b[off] = v;
            writes.push(format!("{off}:{v}"));
        }
        let mut len = b.len();
        let mut resized = String::from("-");
        if rng.below(12) == 0 {
            len = rng.below(b.len() + 16);
            b.resize(len, 0);
            if len >= 32 {
                b[28..32].copy_from_slice(&(len as u32).to_le_bytes());
            }
            resized = len.to_string();
        }
        // 重签：签名段还完整在原位时才有意义，这样才能走到签名之后的装载步骤
        let mut sig = String::from("-");
        if let Some(at) = signed_at {
            if len == originals[i].len() && rng.below(4) != 0 {
                let value = signer.sign(&signed_message(&signed_digest_of(&b, at)));
                b[at + 40..at + 104].copy_from_slice(&value);
                sig = format!("{at}:{}", hex(&value));
            }
        }
        let sealed = len >= 80 && rng.below(10) != 0;
        if sealed {
            reseal(&mut b);
        }
        writeln!(
            out,
            "{}\t{resized}\t{}\t{}\t{sig}\t{}",
            names[i],
            if writes.is_empty() { "-".to_string() } else { writes.join(",") },
            if sealed { "sealed" } else { "raw" },
            expect_line(check(&b, key)).to_string()
        )
        .unwrap();
    }
    out
}

const WS: [&str; 16] = ["", " ", "\t", "  ", "\u{a0}", "\u{2003}", "\u{85}", "\u{3000}", "\u{2028}", "\u{1680}", "\u{0b}", "\u{0c}", "\u{200a}", "\u{2029}", "\u{202f}", "\u{205f}"];

/// 随机清单：大多数以四个必需键打底，再混入合法与畸形的行。
fn manifest_text(rng: &mut Rng, managed: bool) -> Vec<u8> {
    let abi = if managed { "jvm.52" } else { "c.v1" };
    let mut lines: Vec<String> = Vec::new();
    let base = rng.below(10) != 0;
    if base {
        lines.push(format!("core.name = {}", rng.pick(&["jaso-demo", "jaso-x.y_z", "other", "jaso-demo-with-a-name-that-is-long-but-ok"])));
        lines.push(format!("core.version = {}", rng.pick(&["1.0.0", "0.0.1", "999999999.0.0"])));
        lines.push(format!("core.abi = {abi}"));
        lines.push("core.stage = runtime".to_string());
        lines.push(format!("core.publisher = {}", rng.pick(&["jasper.dev", "jasper.dev", "jasper.dev", "someone"])));
    }
    for _ in 0..rng.below(6) {
        let k = rng.below(24);
        let line = match k {
            0 => format!("core.name = {}", rng.pick(&["a", "", "UPPER", "a b", "x\u{e9}", &"n".repeat(64), &"n".repeat(65)])),
            1 => format!("core.version = {}", rng.pick(&["1.0", "1.0.0.0", "1234567890.0.0", "a.b.c", "1..0", "01.02.03", ""])),
            2 => format!("core.abi = {}", rng.pick(&["c.v1", "jvm.52", "C.V1", "", &"a".repeat(16), &"a".repeat(17)])),
            3 => format!("core.stage = {}", rng.pick(&["runtime", "boot", "build", "later", ""])),
            4 => format!("core.lifecycle = {}", rng.pick(&["plugin", "system", "other", ""])),
            5 | 6 => format!(
                "core.runtime = {}",
                rng.pick(&[">=0.1,<0.2", ">=0.1.0", "<0.1", "=0.1.0", "=0.1", ">0.1.0", "<=0.1.0", ">= 0.1", " >=0.1 , <0.2 ", "", ",", ">=0.1,", "0.1", ">=0.1,<0.2,>=0.0,<9.0", ">=0.1,<0.2,>=0.0,<9.0,>=0.0", ">=1", ">=a.b", ">=0.1.0.0", "==0.1"])
            ),
            7..=10 => format!(
                "core.depends = {}",
                rng.pick(&[
                    "jaso-base", "jaso-base@>=1.0,<2.0", "jaso-base@>=2.0", "jaso-base@=1.2", "jaso-base@=1.2.0", "jaso-base @ >=1.0", "missing", "missing@>=1.0",
                    "jaso-base@>=1.0, <2.0, other", "a, b", "a, a", "a,", ",", "", "a@", "@>=1.0", "<2.0", "a@>=1.0,\u{a0}<2.0", "a@b", "UP", "a@>=1.0,<2.0,>0.5,<3.0", "a@>=1.0,<2.0,>0.5,<3.0,>0.6",
                    "a@>=1.0,<2.0,b@>=1.0,c",
                ])
            ),
            11 => format!("core.depends = {}", (0..30 + rng.below(6)).map(|n| format!("d{n}")).collect::<Vec<_>>().join(",")),
            12 | 13 => format!("core.requires = {}", rng.pick(&["log", "alloc", "log, alloc", "net", "log,,alloc", "", " log ", "L", "a.b_c"])),
            14 => format!("core.requires = {}", (0..30 + rng.below(6)).map(|n| format!("c{n}")).collect::<Vec<_>>().join(",")),
            15 => format!("core.host = {}", rng.pick(&["jaso.host.v1", "bad-name", "", &"h".repeat(32), &"h".repeat(33)])),
            16 => format!("core.description = {}", rng.pick(&["free text = with equals", "\u{4e2d}\u{6587}\u{1f600}", "", " padded "])),
            17 => format!("core.publisher = {}", rng.pick(&["jasper.dev", "Bad", "", &"p".repeat(65)])),
            18 => rng.pick(&["core.unknown = 1", "core. = 1", "core = 1", "web.route = /x", "web-x_y.a.b-c = 1", "web. = 1", ".x = 1", "nodot = 1", "Web.x = 1", "web.X = 1", " = 1", "=", "a.b="]).to_string(),
            19 => rng.pick(&["# comment", "", "   ", "no equals line", "\u{a0}# not a comment after nbsp is one", "#=", "\u{85}"]).to_string(),
            20 => format!("core.exports = {}", rng.pick(&["jaso_init", "f.$x_1", "", "a b", "a,,b", "\u{e9}"])),
            21 => format!("core.exports = {}", (0..250 + rng.below(12)).map(|n| format!("e{n}")).collect::<Vec<_>>().join(",")),
            22 => format!("web.k{} = v", rng.below(3)),
            _ => format!("core.lifecycle = plugin"),
        };
        lines.insert(rng.below(lines.len() + 1), line);
    }
    let mut out = Vec::new();
    for l in &lines {
        out.extend_from_slice(rng.pick(&WS).as_bytes());
        // 等号两侧的空白也换成随机种类
        let l2 = if rng.below(3) == 0 { l.replacen(" = ", &format!("{}={}", rng.pick(&WS), rng.pick(&WS)), 1) } else { l.clone() };
        out.extend_from_slice(l2.as_bytes());
        out.extend_from_slice(rng.pick(&WS).as_bytes());
        out.extend_from_slice(rng.pick(&["\n", "\n", "\r\n", "\n\n"]).as_bytes());
    }
    if rng.below(40) == 0 && !out.is_empty() {
        let at = rng.below(out.len());
        out[at] = [0xff, 0xc0, 0xed, 0xf5, 0x80][rng.below(5)];
    }
    out
}

fn dump(m: &Manifest<'_>) -> String {
    let vs = [(0, 1, 0), (1, 2, 0), (2, 0, 0)];
    let range = |r: Option<jaso::VersionRange>| -> String {
        match r {
            None => "*".into(),
            Some(r) => vs.iter().map(|v| if r.contains(*v) { '1' } else { '0' }).collect(),
        }
    };
    let list = |it: Vec<String>| it.join(";");
    format!(
        "ok|{}|{}.{}.{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
        m.name,
        m.version.0,
        m.version.1,
        m.version.2,
        m.abi,
        m.stage.name(),
        match m.lifecycle {
            jaso::Lifecycle::Plugin => "plugin",
            jaso::Lifecycle::System => "system",
        },
        m.host.unwrap_or("~"),
        hex(m.description.unwrap_or("~").as_bytes()),
        m.publisher.unwrap_or("~"),
        range(m.runtime),
        list(m.requires().map(String::from).collect()),
        list(m.exports().map(String::from).collect()),
        list(m.depends().map(|d| format!("{}={}", d.name, range(d.range))).collect()),
    )
}

fn manifests(rng: &mut Rng, cases: usize) -> String {
    let mut out = String::new();
    for _ in 0..cases {
        let managed = rng_bool(rng);
        let text = manifest_text(rng, managed);
        let result = match core::str::from_utf8(&text) {
            Err(_) => "manifest.utf8".to_string(),
            Ok(t) => match Manifest::parse(t) {
                Ok(m) => dump(&m),
                Err(e) => e.code().name().to_string(),
            },
        };
        writeln!(out, "{}\t{result}", hex(&text)).unwrap();
    }
    out
}

fn rng_bool(rng: &mut Rng) -> bool {
    rng.below(2) == 0
}

/// 完整合法结构 + 随机清单 + 开发用密钥签名，走到信任、平台、运行时、依赖、能力各步。
fn load_files(key: [u8; 32], rng: &mut Rng, cases: usize) -> String {
    let signer = development_signer();
    let mut out = String::new();
    let mut made = 0;
    while made < cases {
        let managed = rng_bool(rng);
        let mut text = manifest_text(rng, managed);
        if !managed && rng_bool(rng) {
            text.extend_from_slice(b"core.exports = f_one\n");
        }
        if managed && rng_bool(rng) {
            text.extend_from_slice(b"core.exports = demo.Main.run\n");
        }
        let mut b = Builder::native(Arch::X86_64, Stage::Runtime, &text);
        if managed {
            b.kind = ModuleKind::Managed;
            b.platform = PlatformIn::jvm();
            b.code = Some(b"PK\x05\x06\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0".to_vec());
            b.exports = vec![(b"demo.Main.run".to_vec(), 0)];
        } else {
            b.platform.float_abi = if rng.below(5) == 0 { FloatAbi::Soft } else { FloatAbi::Hard };
            b.segments = vec![SegmentIn { vaddr: 0, mem_size: 16, flags: FLAG_R | FLAG_X, data: vec![0xC3; 16] }];
            b.exports = vec![(b"f_one".to_vec(), 0)];
        }
        let file = if rng.below(8) == 0 { b.finish() } else { b.finish_signed(&signer) };
        // 生成器自身拒绝的（比如清单写不成合法文件）跳过：它们由清单表覆盖
        let Ok(file) = file else { continue };
        writeln!(out, "{}\t{}", hex(&file), expect_line(check(&file, key))).unwrap();
        made += 1;
    }
    out
}

fn signatures(key: [u8; 32], rng: &mut Rng) -> String {
    let signer = development_signer();
    let mut out = String::new();
    let low_order: Vec<Vec<u8>> = [
        "0100000000000000000000000000000000000000000000000000000000000000",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0000000000000000000000000000000000000000000000000000000000000080",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
        "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
        "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85",
        // 非规范编码：y >= p
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    ]
    .iter()
    .map(|s| unhex(s))
    .collect();
    let order = unhex("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010");
    let mut emit = |k: &[u8], msg: &[u8], sig: &[u8]| {
        let mut ka = [0u8; 32];
        ka.copy_from_slice(k);
        let mut sa = [0u8; 64];
        sa.copy_from_slice(sig);
        let parsed = VerifyingKey::from_bytes(&ka);
        let verdict = parsed.as_ref().is_ok_and(|v| v.verify_strict(msg, &Signature::from_bytes(&sa)).is_ok());
        writeln!(out, "{}\t{}\t{}\t{}\t{}", hex(k), hex(msg), hex(sig), parsed.is_ok(), verdict).unwrap();
    };
    // 合法签名及其单点改动
    for n in 0..60 {
        let msg: Vec<u8> = (0..rng.below(80)).map(|_| rng.next() as u8).collect();
        let sig = signer.sign(&msg);
        emit(&key, &msg, &sig);
        let mut bad = sig;
        bad[rng.below(64)] ^= 1 << rng.below(8);
        emit(&key, &msg, &bad);
        if n % 3 == 0 {
            let mut tampered = msg.clone();
            tampered.push(1);
            emit(&key, &tampered, &sig);
        }
        // R 的符号位与末字节低位：-R 仍是曲线点，只有末字节比较能拒绝
        let mut sign_flip = sig;
        sign_flip[31] ^= 0x80;
        emit(&key, &msg, &sign_flip);
        let mut low_flip = sig;
        low_flip[31] ^= 0x01;
        emit(&key, &msg, &low_flip);
        // S + L：同一方程成立但标量非规范
        let mut plus = sig;
        let mut carry = 0u16;
        for i in 0..32 {
            let v = plus[32 + i] as u16 + order[i] as u16 + carry;
            plus[32 + i] = v as u8;
            carry = v >> 8;
        }
        emit(&key, &msg, &plus);
    }
    // 小阶点与非规范编码作公钥、作 R，S 取 0 / 1 / L-1 / L / 随机
    let mut order_minus_one = order.clone();
    order_minus_one[0] -= 1;
    let mut one = vec![0u8; 32];
    one[0] = 1;
    let zero = vec![0u8; 32];
    let scalars: Vec<Vec<u8>> = vec![zero, one, order_minus_one, order.clone()];
    for a in &low_order {
        for r in &low_order {
            for s in &scalars {
                let mut sig = r.clone();
                sig.extend_from_slice(s);
                emit(a, b"jaso", &sig);
            }
        }
        for s in &scalars {
            // 合法公钥 + 小阶 R；小阶公钥 + 随机 R
            let mut sig = a.clone();
            sig.extend_from_slice(s);
            emit(&key, b"jaso", &sig);
            let msg_sig = signer.sign(b"jaso");
            let mut sig2 = msg_sig[..32].to_vec();
            sig2.extend_from_slice(s);
            emit(a, b"jaso", &sig2);
        }
    }
    // 方程成立、只有小阶检查能拒绝的签名：合法公钥 + 小阶 R（s = k·a）；小阶公钥 + 合法 R（s = r）
    let seed = [7u8; 32];
    let expanded: [u8; 64] = Sha512::digest(seed).into();
    let mut a_bytes = [0u8; 32];
    a_bytes.copy_from_slice(&expanded[..32]);
    a_bytes[0] &= 248;
    a_bytes[31] = (a_bytes[31] & 63) | 64;
    let a = Scalar::from_bytes_mod_order(a_bytes);
    let public = EdwardsPoint::mul_base(&a).compress().to_bytes();
    let challenge = |r: &[u8], k: &[u8], m: &[u8]| -> Scalar {
        let wide: [u8; 64] = Sha512::new().chain_update(r).chain_update(k).chain_update(m).finalize().into();
        Scalar::from_bytes_mod_order_wide(&wide)
    };
    for low in &low_order[..8] {
        // R = 单位元时方程恰好成立；其余小阶 R 也一并判定
        let s = challenge(low, &public, b"small") * a;
        let mut sig = low.clone();
        sig.extend_from_slice(s.as_bytes());
        emit(&public, b"small", &sig);
    }
    for n in 1..6u8 {
        let r = Scalar::from_bytes_mod_order([n; 32]);
        let big_r = EdwardsPoint::mul_base(&r).compress().to_bytes();
        for low in &low_order[..8] {
            let mut sig = big_r.to_vec();
            sig.extend_from_slice(r.as_bytes());
            emit(low, b"small", &sig);
        }
    }
    // 持有私钥可以造：哈希用翻转符号位的 R，方程解出原 R，两者只差第 31 字节最高位，只有逐字节比较能拒绝
    for n in 1..6u8 {
        let r = Scalar::from_bytes_mod_order([n ^ 0x55; 32]);
        let mut flipped = EdwardsPoint::mul_base(&r).compress().to_bytes();
        flipped[31] ^= 0x80;
        let s = r + challenge(&flipped, &public, b"sign") * a;
        let mut sig = flipped.to_vec();
        sig.extend_from_slice(s.as_bytes());
        emit(&public, b"sign", &sig);
    }
    // 随机 32 字节作公钥：约一半不是曲线点
    for _ in 0..200 {
        let k: Vec<u8> = (0..32).map(|_| rng.next() as u8).collect();
        let sig: Vec<u8> = (0..64).map(|_| rng.next() as u8).collect();
        emit(&k, b"x", &sig);
    }
    out
}

/// 平台不符的结构化报告：字段、宿主值、文件值都要一致。
fn platforms(key: [u8; 32]) -> String {
    let signer = development_signer();
    let mut out = String::new();
    let manifest = |abi: &str| format!("core.name = jaso-p\ncore.version = 1.0.0\ncore.abi = {abi}\ncore.stage = runtime\ncore.publisher = jasper.dev\ncore.exports = f_one\n");
    let variants: [(Arch, &str, Target, &str, FloatAbi); 8] = [
        (Arch::X86_64, "", Target::JasperOs, "c.v1", FloatAbi::Hard),
        (Arch::Aarch64, "", Target::JasperOs, "c.v1", FloatAbi::Hard),
        (Arch::X86_64, "avx2", Target::JasperOs, "c.v1", FloatAbi::Hard),
        (Arch::X86_64, "avx2,sse4", Target::Host, "c.v2", FloatAbi::Soft),
        (Arch::X86_64, "", Target::Host, "c.v1", FloatAbi::Hard),
        (Arch::X86_64, "", Target::JasperOs, "c.v2", FloatAbi::Hard),
        (Arch::X86_64, "", Target::JasperOs, "c.v1", FloatAbi::Soft),
        (Arch::Riscv32, "i,m", Target::JasperOs, "c.v2", FloatAbi::Soft),
    ];
    for (isa, ext, target, abi, float_abi) in variants {
        let text = manifest(abi);
        let mut b = Builder::native(isa, Stage::Runtime, text.as_bytes());
        b.platform.isa_ext = ext.into();
        b.platform.target = target;
        b.platform.abi = Some(abi.as_bytes().to_vec());
        b.platform.float_abi = float_abi;
        b.segments = vec![SegmentIn { vaddr: 0, mem_size: 16, flags: FLAG_R | FLAG_X, data: vec![0xC3; 16] }];
        b.exports = vec![(b"f_one".to_vec(), 0)];
        let file = b.finish_signed(&signer).unwrap();
        let parsed = Jaso::parse(&file).unwrap();
        let report = match parsed.select(&jaso::conformance::NATIVE_HOST) {
            Ok(_) => "-\t-\t-".to_string(),
            Err(m) => {
                let field = match m.field {
                    PlatformField::Isa => "isa",
                    PlatformField::IsaExt => "isa_ext",
                    PlatformField::Target => "target",
                    PlatformField::Abi => "abi",
                    PlatformField::FloatAbi => "float_abi",
                    PlatformField::PointerBytes => "pointer_bytes",
                };
                format!("{field}\t{}\t{}", m.expected.as_str(), m.actual.as_str())
            }
        };
        writeln!(out, "{}\t{}\t{report}", hex(&file), expect_line(check(&file, key))).unwrap();
    }
    out
}

struct Dir {
    at: usize,
    kind: u32,
    off: usize,
    size: usize,
}

fn directory(b: &[u8]) -> Vec<Dir> {
    let (start, n) = (u32_at(b, 24), u32_at(b, 20));
    (0..n)
        .map(|i| {
            let at = start + i * 32;
            Dir { at, kind: u32_at(b, at) as u32, off: u32_at(b, at + 8), size: u32_at(b, at + 12) }
        })
        .collect()
}

fn put32(b: &mut [u8], at: usize, v: u32) {
    b[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

/// 改完之后重签（如果带签名）并重算摘要。
fn finish_edit(b: &mut Vec<u8>) {
    let signer = development_signer();
    if let Some(d) = directory(b).iter().find(|d| d.kind == 5) {
        if d.off + 104 == b.len() {
            let value = signer.sign(&signed_message(&signed_digest_of(b, d.off)));
            b[d.off + 40..d.off + 104].copy_from_slice(&value);
        }
    }
    reseal(b);
}

/// 定向边界用例：每条规则的边界两侧各一个，随机变异几乎碰不到它们。
fn targeted(dir: &Path, key: [u8; 32]) -> String {
    let read = |n: &str| std::fs::read(dir.join(format!("{n}.jaso"))).unwrap();
    let mut cases: Vec<Vec<u8>> = Vec::new();
    let native = read("ok-native");
    let of = |b: &[u8], kind: u32| directory(b).into_iter().filter(|d| d.kind == kind).collect::<Vec<_>>();
    // 映像大小上限
    for size in [64u32 << 20, (64 << 20) + 4096] {
        let mut b = native.clone();
        put32(&mut b, of(&native, 7)[0].off + 8 + 12, size);
        cases.push(b);
    }
    // 重定位目标：跨出段尾（起点在段内）、段尾刚好
    let segs = of(&native, 2);
    let rw = segs.iter().find(|d| u32_at(&native, d.at + 4) & 2 != 0).unwrap();
    let reloc = &of(&native, 4)[0];
    let base = u32_at(&native, rw.at + 16) as u32;
    for (mem, offset) in [(20u32, base + 16), (24, base + 16), (17, base + 8)] {
        let mut b = native.clone();
        put32(&mut b, rw.at + 20, mem);
        put32(&mut b, reloc.off + 4, offset);
        cases.push(b);
    }
    // addend：最高位为 1、等于和小于 image_size
    let image_size = u32_at(&native, of(&native, 7)[0].off + 8 + 12) as u32;
    for (low, high) in [(0u32, 0x8000_0000u32), (image_size, 0), (image_size - 1, 0), (0, 1)] {
        let mut b = native.clone();
        put32(&mut b, reloc.off + 4 + 8, low);
        put32(&mut b, reloc.off + 4 + 12, high);
        cases.push(b);
    }
    // 代码段：恰好 8 字节（空载荷）、9 字节
    let managed = read("ok-managed");
    for size in [8u32, 9] {
        let mut b = managed.clone();
        put32(&mut b, of(&managed, 9)[0].at + 12, size);
        cases.push(b);
    }
    // 扩展的关键标志与版本：jasper.import / jasper.source 名字等长，原地改写
    let hybrid = read("ok-hybrid");
    let ext = of(&hybrid, 8).into_iter().find(|d| &hybrid[d.off + 8..d.off + 21] == b"jasper.import").unwrap();
    for (name, flag, version) in [
        (&b"jasper.import"[..], 0u32, 1u32),
        (b"jasper.import", 1, 2),
        (b"jasper.import", 0, 2),
        (b"jasper.source", 1, 1),
        (b"jasper.source", 0, 1),
        (b"jasper.source", 1, 2),
    ] {
        let mut b = hybrid.clone();
        b[ext.off + 8..ext.off + 21].copy_from_slice(name);
        put32(&mut b, ext.at + 4, flag);
        put32(&mut b, ext.off, version);
        cases.push(b);
    }
    // 共用平台记录：大小相同放行到后续检查，大小不同拒绝
    let multi = read("ok-multi-image");
    let images = &of(&multi, 7)[0];
    for extra in [0u32, 4096] {
        let mut b = multi.clone();
        let (e0, e1) = (images.off + 8, images.off + 8 + 32);
        b[e1 + 2] = b[e0 + 2];
        b[e1 + 3] = b[e0 + 3];
        let size0 = u32_at(&multi, e0 + 12) as u32;
        put32(&mut b, e1 + 12, size0 + extra);
        cases.push(b);
    }
    // 目录尾部与签名段重叠：最后一个目录项落在签名段开头，scheme = 1 恰好读作 Manifest 种类，
    // 公钥字段写成清单的偏移与长度。结构上其它检查全部成立，只有第 10 节的「目录在签名之前结束」能拒绝。
    let d = directory(&managed);
    let manifest_index = d.iter().position(|x| x.kind == 1).unwrap();
    let sig = d.iter().find(|x| x.kind == 5).unwrap();
    assert_eq!(manifest_index, 0, "基准向量的清单应是目录第 0 项");
    let mut body = managed[..sig.off].to_vec();
    let new_dir = body.len();
    let new_sig = new_dir + (d.len() - 1) * 32;
    for x in &d[1..] {
        let mut entry = managed[x.at..x.at + 32].to_vec();
        if x.kind == 5 {
            entry[8..12].copy_from_slice(&(new_sig as u32).to_le_bytes());
        }
        body.extend_from_slice(&entry);
    }
    let mut trailer = managed[sig.off..sig.off + 104].to_vec();
    trailer[8..12].copy_from_slice(&(d[0].off as u32).to_le_bytes());
    trailer[12..16].copy_from_slice(&(d[0].size as u32).to_le_bytes());
    trailer[16..40].fill(0);
    body.extend_from_slice(&trailer);
    let total = body.len() as u32;
    put32(&mut body, 28, total);
    put32(&mut body, 24, new_dir as u32);
    let images_off = of(&managed, 7)[0].off;
    let first = u32_at(&body, images_off + 8 + 4) as u32;
    put32(&mut body, images_off + 8 + 4, first - 1);
    cases.push(body);

    let mut out = String::new();
    for mut b in cases {
        finish_edit(&mut b);
        writeln!(out, "{}\t{}", hex(&b), expect_line(check(&b, key))).unwrap();
    }
    out
}

/// 绑定表的清单：导出 `p.A.f`、`p.A.g` 与一个生命周期导出；请求能力 `log`、`net`。
const BINDING_MANIFEST: &str = "core.name = jaso-b\ncore.version = 1.0.0\ncore.abi = c.v1\ncore.stage = runtime\ncore.requires = log,net\ncore.exports = p.A.f,p.A.g,jaso_init\n";

/// 第 6a 步的差分：定向边界（嵌套深度、行数、字节数、各种分隔符）+ 随机文法样本及其字节变异。
fn bindings(rng: &mut Rng, cases: usize) -> String {
    let good = "package p\ntype A\ncap log\nfn p.A.f(): void\nfn p.A.g(a: int32, b: own L<S?>?): borrow R?\n";
    let with = |extra: &str| format!("{good}{extra}").into_bytes();
    let nested = |depth: usize| format!("fn p.A.f(): void\nfn p.A.g(): {}T{}\n", "L<".repeat(depth), ">".repeat(depth)).into_bytes();
    let mut inputs: Vec<Vec<u8>> = vec![
        good.as_bytes().to_vec(),
        Vec::new(),
        b"fn p.A.f(): void".to_vec(),
        b"fn p.A.f():void\r\nfn p.A.g( ):void\r\n".to_vec(),
        b"  fn   p.A.f  (  )  :  void  \n\tfn p.A.g():void\t\n".to_vec(),
        "\u{3000}fn p.A.f(): void\u{a0}\n\u{2003}# 注释\u{85}\nfn p.A.g(): void\u{2029}".as_bytes().to_vec(),
        b"fn\tp.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        "fn\u{a0}p.A.f(): void\nfn p.A.g(): void\n".as_bytes().to_vec(),
        b"fn p.A.f(): void\rfn p.A.g(): void\n".to_vec(),
        b"fn p.A.f(): own\nfn p.A.g(): borrow\n".to_vec(),
        b"fn p.A.f(): own T\nfn p.A.g(): borrow  own T\n".to_vec(),
        b"fn p.A.f(): own  T\nfn p.A.g(ownx: ownx): borrowx\n".to_vec(),
        b"fn p.A.f(): T ?\nfn p.A.g(): T??\n".to_vec(),
        b"fn p.A.f(): L <T>\nfn p.A.g(): void\n".to_vec(),
        b"fn p.A.f(): L< T , U >?\nfn p.A.g(a : T , b : U ) : void\n".to_vec(),
        b"fn p.A.f(): L<>\nfn p.A.g(): void\n".to_vec(),
        b"fn p.A.f(a: T,): void\nfn p.A.g(): void\n".to_vec(),
        b"fn p.A.f(): void x\nfn p.A.g(): void\n".to_vec(),
        b"fn p.A.f(): *u8\nfn p.A.g(): &u8\n".to_vec(),
        b"fn p.A.f(): a..b\nfn p.A.g(): .a\n".to_vec(),
        b"fn p.A.f(): 1a\nfn p.A.g(): void\n".to_vec(),
        b"fn p.A.f\nfn p.A.g(): void\n".to_vec(),
        b"fn (): void\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"fn p.A.f(): void\nfn p.A.g(): void\nfn jaso_init(): void\n".to_vec(),
        b"fn p.A.f(): void\nfn p.A.g(): void\nfn p.A.h(): void\n".to_vec(),
        b"fn p.A.f(): void\nfn p.A.g(): void\nfn p.A.g(a: T): void\n".to_vec(),
        b"fn p.A.f(): void\n".to_vec(),
        b"package\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"package a.b.c\npackage a.b.c\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"package a.b\npackage c\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"package a b\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"package a.\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"package  a\ntype   T\ncap   log\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"type a.b\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"type T\ntype T\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"type T\ntype U\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"cap log\ncap log\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"cap log\ncap net\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"cap fs\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"cap log net\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"import x\nfn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"Fn p.A.f(): void\nfn p.A.g(): void\n".to_vec(),
        b"#\n  # x\n\n\r\nfn p.A.f(): void\nfn p.A.g(): void\n#end".to_vec(),
        b"fn p.A.f(): void\nfn p.A.g(): void\n# \xFF\n".to_vec(),
        with("# tail\n"),
        b"type _X1\nfn p.A.f(_a: _T): a_b.c_d\nfn p.A.g(): void\n".to_vec(),
        b"fn p.A.f(): L<T U>\nfn p.A.g(): void\n".to_vec(),
        b"fn p.A.f(a: T b: U): void\nfn p.A.g(): void\n".to_vec(),
        nested(8),
        nested(9),
    ];
    // 符号恰好 128 / 129 字节
    for n in [128usize, 129] {
        inputs.push(format!("fn p.A.f(): void\nfn p.A.g(): void\nfn {}(): void\n", "s".repeat(n)).into_bytes());
    }
    // 有效行恰好 1024 / 1025；只有注释与空行不计数
    for types in [1022usize, 1023] {
        let mut s = String::from("fn p.A.f(): void\nfn p.A.g(): void\n");
        for i in 0..types {
            writeln!(s, "type T{i}\n# c\n").unwrap();
        }
        inputs.push(s.into_bytes());
    }
    // 字节恰好 65536 / 65537
    for size in [65536usize, 65537] {
        let mut b = b"fn p.A.f(): void\nfn p.A.g(): void\n# ".to_vec();
        b.resize(size, b'x');
        inputs.push(b);
    }
    let pieces = ["package p", "type A", "type B", "cap log", "cap net", "cap fs", "# c", "", "fn p.A.f(): void", "fn p.A.g(a: T): U?",
        "fn p.A.g (a: T, b: L<S>): own R", "fn p.A.h(): void", "fn jaso_init(): void"];
    for _ in 0..cases {
        let mut text = String::new();
        // 大半样本带齐两个 fn，否则几乎全部死在「缺声明」。
        if rng.below(4) != 0 {
            text.push_str("fn p.A.f(): void\nfn p.A.g(): void\n");
        }
        for _ in 0..rng.below(5) {
            text.push_str(pieces[rng.below(pieces.len())]);
            text.push('\n');
        }
        let mut b = text.into_bytes();
        for _ in 0..rng.below(3) {
            match rng.below(3) {
                0 if !b.is_empty() => {
                    let at = rng.below(b.len());
                    b[at] = rng.pick(&["(", ")", ":", "<", ">", ",", "?", " ", ".", "\t", "\n", "x", "1", "\r", "#"]).as_bytes()[0];
                }
                1 if !b.is_empty() => {
                    let at = rng.below(b.len());
                    b.remove(at);
                }
                _ => {
                    let at = rng.below(b.len() + 1);
                    b.insert(at, rng.next() as u8);
                }
            }
        }
        inputs.push(b);
    }
    let manifest = Manifest::parse(BINDING_MANIFEST).unwrap();
    let mut out = String::new();
    for b in inputs {
        let result = match jaso::binding::check(&b, &manifest) {
            Ok(()) => "ok",
            Err(e) => e.code().name(),
        };
        writeln!(out, "{}\t{}\t{result}", hex(BINDING_MANIFEST.as_bytes()), hex(&b)).unwrap();
    }
    out
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args[1] == "check-emit" {
        return check_emit(Path::new(&args[2]), development_signer().public_key());
    }
    let dir = Path::new(&args[1]);
    let out = Path::new(&args[2]);
    let key = development_signer().public_key();
    assert_eq!(hex(&key), std::fs::read_to_string(dir.join("jasper.dev.pub")).unwrap().trim());
    let mut rng = Rng(20261151);
    std::fs::write(out.join("diff-files.tsv"), files(dir, key, &mut rng, 6000)).unwrap();
    std::fs::write(out.join("diff-manifests.tsv"), manifests(&mut rng, 3000)).unwrap();
    std::fs::write(out.join("diff-load.tsv"), load_files(key, &mut rng, 600)).unwrap();
    std::fs::write(out.join("diff-bindings.tsv"), bindings(&mut Rng(20261161), 4000)).unwrap();
    std::fs::write(out.join("diff-targeted.tsv"), targeted(dir, key)).unwrap();
    std::fs::write(out.join("diff-platforms.tsv"), platforms(key)).unwrap();
    std::fs::write(out.join("diff-signatures.tsv"), signatures(key, &mut rng)).unwrap();
    ExitCode::SUCCESS
}

/// `check-emit <目录>`：核对 Jasper 编译器写出的文件（JB-L2）。
/// 每个 `<名>.jaso` 须通过结构读取；用参考构建器按解析结果重建须逐字节相同；
/// 用开发密钥签名后按一致性上下文判定，结果须等于 `<名>.expect`。
fn check_emit(dir: &Path, key: [u8; 32]) -> ExitCode {
    let signer = development_signer();
    let mut names: Vec<_> = std::fs::read_dir(dir)
        .unwrap()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "jaso"))
        .collect();
    names.sort();
    let mut failed = 0;
    for path in &names {
        let bytes = std::fs::read(path).unwrap();
        let stem = path.file_stem().unwrap().to_string_lossy().into_owned();
        let f = match Jaso::parse(&bytes) {
            Ok(f) => f,
            Err(e) => {
                println!("{stem}\trejected\t{}", e.code().name());
                failed += 1;
                continue;
            }
        };
        let builder = Builder::from_file(&f, 0).expect("文件能重建");
        let same = builder.finish().unwrap() == bytes;
        let signed = builder.finish_signed(&signer).unwrap();
        let got = expect_line(check(&signed, key));
        let want = std::fs::read_to_string(path.with_extension("expect")).unwrap();
        let ok = same && got == want.trim();
        println!("{stem}\tbytes_equal={same}\tsigned={got}\texpected={}", want.trim());
        if !ok {
            failed += 1;
        }
    }
    println!("check-emit: {} files, {failed} failed", names.len());
    if failed == 0 && !names.is_empty() { ExitCode::SUCCESS } else { ExitCode::from(1) }
}
