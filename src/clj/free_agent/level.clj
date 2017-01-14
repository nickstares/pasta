;;; This software is copyright 2016 by Marshall Abrams, and
;;; is distributed under the Gnu General Public License version 3.0 as
;;; specified in the file LICENSE.

;; Based on Rafal Bogacz's, "A Tutorial on the Free-energy Framework 
;; for Modelling Perception and Learning", _Journal of Mathematical 
;; Psychology_ (online 2015), http://dx.doi.org/10.1016/j.jmp.2015.11.003

;; SEE doc/level.md for documentation on general features of the code below.

(ns free-agent.level
  (:require ;[clojure.spec :as s]
            [clojure.core.matrix :as m]
            [utils.string :as us]))

;;;;;;;;;;;;;;;;;;;;;
(declare hypoth-inc next-hypoth 
         error-inc next-error 
         covar-inc next-covar
         learn-inc next-learn
         next-level next-levels
         m-square)

(def scalar-covar-min 1.0)

;;;;;;;;;;;;;;;;;;;;;
;; Level

(defrecord Level [hypoth error covar learn ; names from Bogacz
                  gen gen'             ; h, h'
                  attn
                  hypoth-dt error-dt covar-dt learn-dt]) ; increment sizes for approaching limit

(def Level-docstring
  "\n  A Level records values at one level of a prediction-error/free-energy
  minimization model.  Variable names in Bogacz's paper are in parentheses.
  hypoth:  Current value of input at this level, or generative function parameter.
  At the first (zeroth) level this is sensory data which may vary quite 
  a lot from one timestep to another.  At higher levels this represents 
  parameters of hypotheses, or hypotheses about parameter values. (phi)
  err:    The error at this level. (epsilon)
  covar:  Covariance matrix or variance of assumed distribution over inputs 
          at this level.  Variance should usually be >= 1 (p. 5 col 2).  (Sigma)
  learn:  Scaling factor learn (scalar or matrix) for generative function.  When 
          learn is multiplied by result of gen(hypoth), the result is the current 
          estimated mean of the assumed distrubtion.  (theta)
          i.e. g(hypoth) = learn * gen(hypoth), where '*' here is scalar or matrix 
          multiplication as appropriate.
  attn:   Function that can be used to adjust the value of covar in error-inc.
  <x>-dt:  A scalar multiplier (e.g. 0.01) determining how fast <x> is updated.
  gen, gen': See learn; gen' is the derivative of gen.  These never change.

  All of these notations are defined in Bogacz's \"Tutorial\" paper.
  hypoth and error can be scalars, in which case learn and covar are as well.  
  Or hypoth and error can be vectors of length n, in which case covar and learn
  are n x n square matrices.  gen and gen' are functions that can be applied to 
  hypoth.  See doc/level.md for more information.")

(us/add-to-docstr! ->Level    Level-docstring)
(us/add-to-docstr! map->Level Level-docstring)

;;;;;;;;;;;;;;;;;;;;;
;; Functions to calculate next state of system

(defn next-level
  "Returns the value of this level for the next timestep."
  [[-level level +level]]
  (assoc level 
         :hypoth (next-hypoth -level level)
         :error  (next-error   level +level)
         :covar  (next-covar   level)
         :learn  (next-learn   level +level)))

;; See notes in levels.md on this function.
(defn next-levels
  "Given a functions for updating gen, gen', and a bottom-level creation function
  that accepts two levels (its level and the next up), along with a sequence of 
  levels at one timestep, returns a vector of levels at the next timestep.  
  The top level will be used to calculate the next level down, but won't be 
  remade; it will be used again, as is, as the new top level."
  [next-bottom [level-0 level-1 :as levels]]
  (cons (next-bottom [level-0 level-1])     ; Bottom level is special case.
        (conj
          (vec (map next-level            ; Each middle level depends on levels
                    (partition 3 1 levels))) ;  immediately below and above it.
          (last levels))))                ; Top is carried forward as is

;; To see that it's necessary to calculate the error in the usual way
;; at the bottom level, cf. e.g. eq (14) in Bogacz.
(defn make-next-bottom
  "Returns a function similar to next-level, but in which the new hypoth is
  generated by hypoth-generator rather than being calculated in the normal way
  using the error error from the next level down.  gen' is not needed since
  it's only used by the normal hypoth calculation process.  The hypoth produced by
  hypoth-generator represents sensory input from outside the system."
  [hypoth-generator]
  (fn [[level +level]]
    (assoc level 
           :hypoth (hypoth-generator)
           :error (next-error level +level)
           :covar (next-covar level)
           :learn (next-learn level +level))))

(defn make-top-level
  "Makes a top level with constant value hypoth for :hypoth.  Also sets :gen to
  the identity function, which the next level down will use to update eps.
  Other fields will be nil."
  [hypoth]
  (map->Level {:hypoth hypoth :gen identity})) ; other fields will be nil, normally
;; DEBUG: 
; :error 0.01 :covar 0.01 :learn 0.01 :gen' identity :hypoth-dt 0.01 :error-dt 0.01 :covar-dt 0.01 :learn-dt 0.01}))


;(defn next-levels-3
;  "Version of next-levels that may be more efficient with exactly three levels.
;  Given a functions for updating gen, gen', and a bottom-level creation function
;  that accepts two levels (its level and the next up), along with a sequence of 
;  levels at one timestep, returns a vector of levels at the next timestep.  
;  The top level will be used to calculate the next level down, but won't be 
;  remade; it will be used again, as is, as the new top level."
;  [next-bottom [level-0 level-1 :as levels]]
;  [(next-bottom [level-0 level-1]) ; Bottom level is special case.
;   (next-level levels)             ; Each middle level depends on levels immediately below and above it.
;   (last levels)])                 ; top is carried forward as-is



;;;;;;;;;;;;;;;;;;;;;
;; HYPOTH, PHI update

(defn hypoth-inc
  "Calculates slope/increment to the next 'hypothesis' parameter hypoth from 
  the current hypoth using the error -error from the level below, scaled by
  the generative function scaling factor learn and the derivative gen' of 
  the generative function gen at this level, and subtracting the error at 
  this level.  See equations (44), (53) in Bogacz's \"Tutorial\"."
  [hypoth error -error -learn gen']
  (m/sub (m/mul (gen' hypoth)                          ; From the generative value of the hypoth at this level
                (m/mmul (m/transpose -learn) -error))  ; scaled by the learn-multiplied error from the level below,
         error))                                       ; subtract the error at this level.

(defn next-hypoth 
  "Calculates the next-timestep 'hypothesis' hypoth from this level 
  and the one below."
  [-level level]
  (let [{:keys [hypoth hypoth-dt error gen']} level
        -error (:error -level)
        -learn (:learn -level)]
    (m/add hypoth 
           (m/mul hypoth-dt
                  (hypoth-inc hypoth error -error -learn gen')))))

;;;;;;;;;;;;;;;;;;;;;
;; ERROR, EPSILON update

(defn error-inc 
  "Calculates the slope/increment to the next 'error' error from 
  the current error, using the mean of the generative model at the
  next level up, but scaling the current error by the variance/cov-matrix
  at this level, and making the whole thing relative to hypoth at this level.
  See equation (54) in Bogacz's \"Tutorial\", where this value is epsilon.
  attn adjust the value of covar to in order to implement attention."
  [error hypoth +hypoth covar learn +gen attn]
  (m/sub hypoth 
         (m/mmul learn (+gen +hypoth))
         (m/mmul (attn covar) error)))

(defn next-error
  "Calculates the next-timestep 'error' error from this level and the one
  above.  If attn is present, it will be passed to error-inc with level as its
  first argument in order to scale covar.  (Error is epsilon in Bogacz.)"
  [level +level]
  (let [{:keys [hypoth error error-dt covar learn attn]} level
        +hypoth (:hypoth +level)
        +gen (:gen +level)
        increment (error-inc error hypoth +hypoth covar learn +gen (partial attn level))]
    (m/add error (m/mul error-dt increment))))

;(defn old-next-error
;  "Calculates the next-timestep 'error' error from this level and the one
;  above.  epsilon in Bogacz."
;  ([level +level attn]
;  (let [{:keys [hypoth error error-dt covar learn]} level
;        +hypoth (:hypoth +level)
;        +gen (:gen +level)
;        scaled-increment (m/mul error-dt (error-inc error hypoth +hypoth covar learn +gen attn))]
;    (m/add error scaled-increment)))

;;;;;;;;;;;;;;;;;;;;;
;; COVAR, SIGMA update

(def limit-covar identity) ; FIXME for matrix covar (see docstring below)
;(defn limit-covar
;  "If covar is a scalar variance or a single-element vector or matrix, then clip the
;  value to be no less than covar-min.  If covar is a covariance matrix of at least 
;  2x2 size, just return it as is, because I'm not yet sure how to limit it.  
;  (Using determinant > n? positive definite? Neither's widely implemented in core.matrix.)"
;  [covar]
;  (letfn [(mat-max [x y] (m/matrix [[(max x y)]]))]
;    (case (m/shape covar)
;      nil   (max     covar scalar-covar-min)
;      [1]   (mat-max covar scalar-covar-min)
;      [[1]] (mat-max covar scalar-covar-min)
;      covar)))


;; TODO IS THIS RIGHT? IS THIS THE CORRECT INTERPRETATION OF (55)?
;; Well also cf. 71 and the answer to exercise 5.
(defn covar-inc
  "Calculates the slope/increment to the next covar from the current covar,
  i.e. the variance or the covariance matrix of the distribution of inputs 
  at this level.  See equation (55) in Bogacz's \"Tutorial\", where this term
  is represented by a capital Sigma.  (Note this function uses matrix inversion 
  for vector/matrix calcualtions, a non-Hebbian calculation, rather than the 
  local update methods of Bogactz section 5.)"
  [error covar]
  (if-let [covar-inverse (m/inverse covar)] ; inverse returns nil if singular
    (m/mul 0.5 (m/sub (m-square error)
                      covar-inverse))
    (throw (Exception. (str "free-agent.levels/covar-inc: Can't invert singular matrix " covar)))))

(defn next-covar
  "Calculates the next-timestep covar, i.e. the variance or the covariance 
  matrix of the distribution of inputs at this level.  Sigma in Bogacz."
  [level]
  (let [{:keys [error covar covar-dt]} level]
    (limit-covar
      (m/add covar
             (m/mul covar-dt
                    (covar-inc error covar))))))


;;;;;;;;;;;;;;;;;;;;;
;; LEARN, THETA update

;; TODO IS THIS RIGHT? IS THIS THE CORRECT INTERPRETATION OF (56)?
(defn learn-inc
  "Calculates the slope/increment to the next learn component of the mean
  value function from the current learn using the error error at this level
  along with the mean of the generative function at the next level up.  
  See equation (56) in Bogacz's \"Tutorial\", where it is represente by theta."
  [error +hypoth +gen]
  (m/mmul error 
          (m/transpose (+gen +hypoth))))

(defn next-learn
  "Calculates the next-timestep learn component of the mean value function
  from this level and the one above.  theta in Bogacz."
  [level +level]
  (let [{:keys [error learn learn-dt]} level
        +hypoth (:hypoth +level)
        +gen (:gen +level)]
    (m/add learn
           (m/mul learn-dt
                  (learn-inc error +hypoth +gen)))))

;;;;;;;;;;;;;;;;;;;;;
;; Utility functions

(defn m-square
  "Calculates the matrix or scalar square of a value."
  [x]
  (m/mmul x (m/transpose x)))

(defn fmt-level
  "Transform a Level containing vectorz matrices into one
  containing persistent-vector matrices (i.e. Clojure vectors)
  for easier readability at the repl."
  [x]
  (if (sequential? x)
    (map fmt-level x)
    (reduce-kv (fn [m k v] 
                 (assoc m k 
                   (if (m/matrix? v)
                     (m/matrix :persistent-vector v)
                     v)))
               {}
               x)))
 
(def fl fmt-level)

;(defn print-level
;  [level]
;  (doseq [[k v] level] ; level is a record/map, i.e. collection of map-entries
;    (when (and v (not (instance? clojure.lang.IFn v))) ; nils, fns: uninformative
;      (println k)
;      (pm v))))

;(defn print-stage
;  [stage]
;  (doseq [level stage] ; stage is a sequence of levels
;    (print-level level)
;    (println)))


;;;;;;;;;;;;;;;;;;;;;
;; spec

;; (s/def ::pos-num (s/and number? pos?)) ; doesn't work. why?
;; 
;; (s/def ::hypoth number?)
;; (s/def ::error number?)
;; (s/def ::covar ::pos-num)
;; (s/def ::learn number?)
;; 
;; (s/def ::hypoth-dt ::pos-num)
;; (s/def ::error-dt ::pos-num)
;; (s/def ::covar-dt ::pos-num)
;; (s/def ::learn-dt ::pos-num)
;; 
;; (s/def ::level-params 
;;   (s/keys :req-un [::hypoth ::error ::covar ::learn
;;                    ::hypoth-dt ::error-dt ::covar-dt ::learn-dt]))
