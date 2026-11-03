; Arithmetic facts the `.jaso` reader relies on, checked with Z3 over 64-bit vectors.
; Every query negates a fact, so `unsat` means the fact holds; the final query is a
; deliberately wrong fact that must come back `sat`, proving the setup can fail.
; Source: jasper-compiler/src/main/resources/stdlib/jaso/{JasoBytes,JasoFile,Ed25519}.jas
(set-logic QF_BV)

; 1. JasoImage.roundUp: for n < 2^32, ((n + 4095) >> 12) << 12 is the next page multiple.
(push 1)
(declare-const n (_ BitVec 64))
(define-fun r () (_ BitVec 64) (bvshl (bvlshr (bvadd n (_ bv4095 64)) (_ bv12 64)) (_ bv12 64)))
(assert (bvult n (_ bv4294967296 64)))
(assert (not (and (bvuge r n)
                  (= (bvand r (_ bv4095 64)) (_ bv0 64))
                  (bvult (bvsub r n) (_ bv4096 64))
                  (bvslt r (_ bv8589934592 64)))))
(check-sat)
(pop 1)

; 2. JasoFile.parseExtension: content starts at the next multiple of 8 after the name, for name_len 1..=64.
(push 1)
(declare-const l (_ BitVec 32))
(define-fun c () (_ BitVec 32) (bvshl (bvlshr (bvadd (bvadd (_ bv8 32) l) (_ bv7 32)) (_ bv3 32)) (_ bv3 32)))
(assert (and (bvuge l (_ bv1 32)) (bvule l (_ bv64 32))))
(assert (not (and (bvuge c (bvadd (_ bv8 32) l))
                  (= (bvand c (_ bv7 32)) (_ bv0 32))
                  (bvult (bvsub c (bvadd (_ bv8 32) l)) (_ bv8 32))
                  (bvslt c (_ bv80 32)))))
(check-sat)
(pop 1)

; 3. Section bounds: with offset, size < 2^32 the sum cannot wrap in int64, and once
;    offset + size <= len with len < 2^31, both fit a non-negative int32.
(push 1)
(declare-const off (_ BitVec 64))
(declare-const sz (_ BitVec 64))
(declare-const len (_ BitVec 64))
(assert (bvult off (_ bv4294967296 64)))
(assert (bvult sz (_ bv4294967296 64)))
(assert (bvult len (_ bv2147483648 64)))
(assert (bvsle (bvadd off sz) len))
(assert (not (and (bvsge (bvadd off sz) (_ bv0 64))
                  (bvult off (_ bv2147483648 64))
                  (bvult (bvadd off sz) (_ bv2147483648 64)))))
(check-sat)
(pop 1)

; 4. Table sizes: 4 + count * 16 and 8 + count * 64 cannot wrap in int64 for any u32 count.
(push 1)
(declare-const cnt (_ BitVec 64))
(assert (bvult cnt (_ bv4294967296 64)))
(assert (not (and (bvslt (bvadd (_ bv4 64) (bvmul cnt (_ bv16 64))) (_ bv137438953472 64))
                  (bvsge (bvadd (_ bv4 64) (bvmul cnt (_ bv16 64))) (_ bv0 64))
                  (bvslt (bvadd (_ bv8 64) (bvmul cnt (_ bv64 64))) (_ bv549755813888 64))
                  (bvsge (bvadd (_ bv8 64) (bvmul cnt (_ bv64 64))) (_ bv0 64)))))
(check-sat)
(pop 1)

; 5. JasoBytes.u32 and u64: OR of shifted halves equals the arithmetic composition; a negative
;    u64 bit pattern is exactly a value with the top bit set, i.e. >= any image size.
(push 1)
(declare-const lo16 (_ BitVec 64))
(declare-const hi16 (_ BitVec 64))
(assert (bvult lo16 (_ bv65536 64)))
(assert (bvult hi16 (_ bv65536 64)))
(assert (not (= (bvor lo16 (bvshl hi16 (_ bv16 64))) (bvadd lo16 (bvmul hi16 (_ bv65536 64))))))
(check-sat)
(pop 1)
(push 1)
(declare-const v (_ BitVec 64))
(assert (not (= (bvslt v (_ bv0 64)) (bvuge v (_ bv9223372036854775808 64)))))
(check-sat)
(pop 1)

; 6. Ed25519.mul: if every limb satisfies |a| <= 2^18, each product fits in 2^36 without wrapping.
(push 1)
(declare-const a (_ BitVec 64))
(declare-const b (_ BitVec 64))
(define-fun bound ((x (_ BitVec 64))) Bool (and (bvsle x (_ bv262144 64)) (bvsge x (bvneg (_ bv262144 64)))))
(assert (bound a))
(assert (bound b))
(assert (not (and (bvsle (bvmul a b) (_ bv68719476736 64)) (bvsge (bvmul a b) (bvneg (_ bv68719476736 64))))))
(check-sat)
(pop 1)

; 7. Ed25519.mul: 16 such products, then one folded column weighted 38, still fit far below 2^63.
(push 1)
(declare-const column (_ BitVec 64))
(declare-const high (_ BitVec 64))
(define-fun lim () (_ BitVec 64) (_ bv1099511627776 64))
(assert (and (bvsle column lim) (bvsge column (bvneg lim))))
(assert (and (bvsle high lim) (bvsge high (bvneg lim))))
(assert (not (and (bvsle (bvadd column (bvmul (_ bv38 64) high)) (_ bv43980465111040 64))
                  (bvsge (bvadd column (bvmul (_ bv38 64) high)) (bvneg (_ bv43980465111040 64))))))
(check-sat)
(pop 1)

; 8. Sanity: a wrong rounding constant must be refuted (expected: sat).
(push 1)
(declare-const n2 (_ BitVec 64))
(assert (bvult n2 (_ bv4294967296 64)))
(assert (not (bvuge (bvshl (bvlshr (bvadd n2 (_ bv4094 64)) (_ bv12 64)) (_ bv12 64)) n2)))
(check-sat)
(pop 1)
