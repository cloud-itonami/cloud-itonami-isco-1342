(ns healthsvc.advisor
  "HealthServicesManagersAdvisor — proposes a staffing operation
  (approve a staffing plan, approve an emergency surge waiver) for a
  registered organization. Swappable mock/llm; the advisor ONLY
  proposes — `healthsvc.governor` checks the staffing ratio and
  license window independently. Modeled on cloud-itonami-isco-4311's
  advisor.

  A proposal: {:op :approve-staffing-plan|:approve-emergency-surge
               :effect :propose :unit-id str :patient-count number
               :staff-count number :as-of-day int :stake kw
               :confidence n :rationale str}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake unit-id patient-count staff-count as-of-day] :as request}]
  {:op op
   :effect :propose
   :unit-id unit-id
   :patient-count patient-count
   :staff-count staff-count
   :as-of-day as-of-day
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a health services management advisor. Given a request,
   propose an :op, the :unit-id, :patient-count, :staff-count and
   :as-of-day, an honest :confidence and a :stake. Never call an
   over-ratio staffing plan or an expired-license plan conforming —
   the governor checks both against the registered unit record.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
