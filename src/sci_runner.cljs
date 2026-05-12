(ns sci-runner
  (:require
   [sci.core :as sci]
   [cljs.core.async :refer [take! chan put! close!]]
   [cljs.js :as cljs]
   [cljs.analyzer :as ana]
   [cljs.env :as env]
   [shadow.cljs.bootstrap.browser :as boot]
   [cljs.core.async :as async]
   [re-frame.core :as rf]
   [reagent.core :as r]
   [sci.configs.reagent.reagent :as sci-reagent]
   [sci.configs.re-frame.re-frame :as sci-re-frame]
   [clojure.string]
   [cljs-workers.core :as main]
   [sci-shared]
   [promesa.core]
   ["@capacitor/core" :refer [Capacitor]]
   ["@capacitor/browser" :refer [Browser] :as browser]
   [client.state :as state]
   [auth.events]
   [container.base]
   [container.call.call-container]
   [container.call.call-view]
   [container.call.core]
   [container.call.events]
   [container.members]
   [container.pins]
   [container.reusable]
   [container.search]
   [container.timeline.base]
   [container.timeline.components]
   [container.timeline.item]
   [input.autocomplete]
   [input.base]
   [input.composer]
   [input.drafts]
   [input.emotes]
   [navigation.rooms.entry]
   [navigation.rooms.room-list]
   [navigation.rooms.room-summary]
   [navigation.spaces.bar]
   [overlays.invites]
   [overlays.lightbox]
   [overlays.notifications]
   [overlays.profiles]
   [overlays.quick-switcher]
   [overlays.reactions]
   [overlays.settings]
   [utils.logger]
   [utils.helpers]
   [utils.images]
   [utils.global-ui]
   [utils.svg]
   [utils.macros :refer [expose-ns]]))




(defonce !current-eval-plugin (atom nil))

(def defoverride-macro
  (sci/new-macro-var
   'defoverride
   (fn [_form _env comp-name args & body]
     (let [plugin-id @!current-eval-plugin]
       (list 'do
             (list* 'defn comp-name args body)
             (list 'swap! 'client.state/!active-overrides 'assoc (keyword (name comp-name))
                   (list 'hash-map :plugin-id plugin-id :fn comp-name)))))))

(def log-info-macro (sci/new-macro-var 'info (fn [form _env & args] (list* 'taoensso.timbre/plugin-info @!current-eval-plugin (:line (meta form)) args))))
(def log-warn-macro (sci/new-macro-var 'warn (fn [form _env & args] (list* 'taoensso.timbre/plugin-warn @!current-eval-plugin (:line (meta form)) args))))
(def log-error-macro (sci/new-macro-var 'error (fn [form _env & args] (list* 'taoensso.timbre/plugin-error @!current-eval-plugin (:line (meta form)) args))))
(def log-debug-macro (sci/new-macro-var 'debug (fn [form _env & args] (list* 'taoensso.timbre/plugin-debug @!current-eval-plugin (:line (meta form)) args))))

(defn safe-reg-slot-item [slot-id item]
  (state/reg-slot-item slot-id (assoc item :plugin-id @!current-eval-plugin)))


(def plugin-context
  (sci/init
   {:namespaces
    (merge
     sci-shared/common-namespaces
     (:namespaces sci-reagent/config)
     (:namespaces sci-re-frame/config)
     {'user {'defoverride defoverride-macro}
      'capacitor.browser {'Browser Browser}
      'capacitor.core    {'Capacitor Capacitor}
      'sci-runner {'!current-eval-plugin !current-eval-plugin}

      'taoensso.timbre {'info  log-info-macro
                        'warn  log-warn-macro
                        'error log-error-macro
                        'debug log-debug-macro
                        'plugin-info  utils.logger/plugin-info
                        'plugin-warn  utils.logger/plugin-warn
                        'plugin-error utils.logger/plugin-error
                        'plugin-debug utils.logger/plugin-debug}
      'auth.events (expose-ns auth.events)
      'container.base (expose-ns container.base)
      'container.call.core (expose-ns container.call.core)
      'container.call.call-view (expose-ns container.call.call-view)
      'container.call.call-container (expose-ns container.call.call-container)
      'container.call.events (expose-ns container.call.events)
      'container.members (expose-ns container.members)
      'container.pins (expose-ns container.pins)
      'container.reusable (expose-ns container.reusable)
      'container.search (expose-ns container.search)
      'container.timeline.components (expose-ns container.timeline.components)
      'container.timeline.base (expose-ns container.timeline.base)
      'container.timeline.item (expose-ns container.timeline.item)
      'input.autocomplete (expose-ns input.autocomplete)
      'input.base (expose-ns input.base)
      'input.drafts (expose-ns input.drafts)
      'input.emotes (expose-ns input.emotes)
      'input.composer (expose-ns input.composer)
      'navigation.rooms.entry (expose-ns navigation.rooms.entry)
      'navigation.rooms.room-summary (expose-ns navigation.rooms.room-summary)
      'navigation.rooms.room-list (expose-ns navigation.rooms.room-list)
      'navigation.spaces.bar (expose-ns navigation.spaces.bar)
      'overlays.invites (expose-ns overlays.invites)
      'overlays.lightbox (expose-ns overlays.lightbox)
      'overlays.notifications (expose-ns overlays.notfications)
      'overlays.profiles (expose-ns overlays.profiles)
      'overlays.quick-switcher (expose-ns overlays.quick-switcher)
      'overlays.reactions (expose-ns overlays.reactions)
      'overlays.settings (expose-ns overlays.settings)
      'utils.macros   (expose-ns utils.macros)
      'utils.global-ui (expose-ns utils.global-ui)
      'utils.helpers  (expose-ns utils.helpers)
      'utils.images   (expose-ns utils.images)
      'utils.svg      (expose-ns utils.svg)
      'client.state  {'!components state/!components
                      'reg-slot-item safe-reg-slot-item
                      '!active-overrides state/!active-overrides
                      'remove-plugin-overrides! state/remove-plugin-overrides!
                      'get-slot state/get-slot
                      '!config state/!config
                      '!engine-pool state/!engine-pool}
      'cljs-workers.core {'do-with-pool! main/do-with-pool!}})

    :classes sci-shared/common-classes}))

(defn evaluate-ui-form [plugin-id form-str]
  (reset! !current-eval-plugin plugin-id)
  (sci/eval-string* plugin-context form-str))

