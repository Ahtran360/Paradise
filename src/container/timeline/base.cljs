(ns container.timeline.base
  (:require [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [re-frame.db :as db]
            [cljs-workers.core :as main]
            [cljs.core.async :refer [go <!]]
            [reagent.core :as r]
            [client.state :as state]
            [reagent.dom.client :as rdom]
            [utils.images :refer [mxc->url]]
            [utils.helpers :refer [sanitize-custom-html format-divider-date format-readers join-names get-status-string process-raw-event]]
            [utils.global-ui :refer [avatar long-press-props swipe-to-action-wrapper]]
            [utils.svg :as icons]
            [virtualizer.core :refer [virtualized-list]]
            [container.timeline.item :refer [calc-item-height event-tile connected-event-tile]]
            [virtualizer.core :refer [virtualized-list]]
            [container.timeline.components :refer [timeline-empty-state
                                                   timeline-jump-button
                                                   timeline-loading-overlay
                                                   timeline-measuring-sticks
                                                   extract-timeline-metrics]]
            [container.reusable :refer [room-header]]
            [input.base :refer [message-input]]))

(defn get-event-id [e]
  (cond
    (and (= (:type e) :virtual) (str/includes? (str (:tag e)) "Date"))
    (str "virtual-divider-" (format-divider-date (:ts e)) "-" (:ts e))

    (and (= (:type e) :virtual) (str/includes? (str (:tag e)) "Read"))
    "read-marker"

    (= (:type e) :virtual)
    (str "virtual-" (:tag e) "-" (:ts e))

    (not-empty (:id e))
    (:id e)

    :else
    (:internal-id e)))

(defn enrich-timeline-items [items]
  (loop [remaining items
         processed (transient [])
         last-msg  nil
         unread?   false]
    (if (empty? remaining)
      (persistent! processed)
      (let [curr             (first remaining)
            is-marker?       (= (:tag curr) "ReadMarker")
            next-unread?     (or unread? is-marker?)
            curr-is-msg?     (= (:content-tag curr) "MsgLike")
            stable-id        (get-event-id curr)
            can-merge?       (boolean (and curr-is-msg?
                                           last-msg
                                           (= (:sender-id curr) (:sender-id last-msg))
                                           (< (- (:ts curr) (:ts last-msg)) 300000)))
            apply-unread?    (and next-unread? (not is-marker?))
            already-correct? (and (= (:id curr) stable-id)
                                  (= (boolean (:merge-with-prev? curr)) can-merge?)
                                  (= (boolean (:unread? curr)) apply-unread?))
            new-item         (if already-correct?
                               curr
                               (assoc curr
                                      :id stable-id
                                      :merge-with-prev? can-merge?
                                      :unread? apply-unread?))]
        (recur (rest remaining)
               (conj! processed new-item)
               (if curr-is-msg? new-item nil)
               next-unread?)))))


(re-frame/reg-sub
 :timeline/raw-events
 (fn [db [_ room-id]]
   (let [focused-data (get-in db [:timeline-data room-id :focused] [])
         live-data    (get-in db [:timeline-data room-id :live] [])]
;;     live-data
(if (and (vector? focused-data) (empty? focused-data))
  live-data
  focused-data

 ;;    (if (empty? focused-data) focused-data live-data)
     ))))

(re-frame/reg-event-db
 :sdk/update-timeline
 (fn [db [_ room-id source new-raw-events]]
   (if (get-in db [:timeline-subs room-id source])
     (let [path             [:timeline-data room-id source]
           existing-events  (get-in db path [])
           extract-id       (fn [e]
                              (let [e-kw (if (map? e) (update e :type keyword) e)]
                                (or (get-event-id e-kw)
                                    (:event_id e)
                                    (:event-id e)
                                    (:internal-id e))))
           existing-map     (into {} (map (juxt extract-id identity) existing-events))
           processed-events (mapv
                             (fn [e]
                               (let [event-id     (extract-id e)
                                     cached-event (get existing-map event-id)]
                                 (if (and cached-event (= (:raw cached-event) e))
                                   cached-event
                                   (process-raw-event e source))))
                             new-raw-events)]
       (assoc-in db path processed-events))
     (do
       (log/warn "Ghost diff dropped: Timeline" source "is dead for room:" room-id)
       db))))

(re-frame/reg-sub
 :timeline/sorted-events
 (fn [[_ active-room]]
   (re-frame/subscribe [:timeline/raw-events active-room]))
 (fn [raw-events _]
   raw-events
   ))

(re-frame/reg-sub
 :timeline/current-events
 (fn [[_ active-room]]
   (re-frame/subscribe [:timeline/sorted-events active-room]))
 (fn [sorted-events]
   (enrich-timeline-items sorted-events)
   ))

(re-frame/reg-sub
 :timeline/events-map
 (fn [[_ room-id]]
   (re-frame/subscribe [:timeline/current-events room-id]))
 (fn [events _]
   (into {} (map (juxt :id identity) events))))

(re-frame/reg-sub
 :timeline/event
 (fn [[_ room-id _event-id]]
   (re-frame/subscribe [:timeline/events-map room-id]))
 (fn [events-map [_ _room-id event-id]]
   (get events-map event-id)))

(re-frame/reg-event-fx
 :sdk/boot-timeline
 (fn [{:keys [db]} [_ room-id]]
   (if (get-in db [:timeline-subs room-id :live])
     (do (log/warn "Prevented duplicate timeline boot for:" room-id) {})
     (do
       (main/do-with-pool! @state/!engine-pool
                           {:handler :boot-timeline
                            :arguments {:room-id room-id :mode :live}})
       {:db (assoc-in db [:timeline-subs room-id :live] true)}))))


(re-frame/reg-event-fx
 :room/boot-focused-timeline
 (fn [{:keys [db]} [_ room-id event-id]]
   (main/do-with-pool! @state/!engine-pool
                       {:handler :boot-timeline
                        :arguments {:room-id room-id :mode :focused :event-id event-id}})
   {:db (assoc-in db [:timeline-subs room-id :focused] true)}))


(re-frame/reg-event-fx
 :room/boot-pinned-timeline
 (fn [{:keys [db]} [_ room-id]]
   (main/do-with-pool! @state/!engine-pool
                       {:handler :boot-timeline
                        :arguments {:room-id room-id :mode :pins}})
   {:db (assoc-in db [:timeline-subs room-id :pins] true)}))

(re-frame/reg-event-fx
 :sdk/cleanup-timeline
 (fn [{:keys [db]} [_ room-id]]
   (main/do-with-pool! @state/!engine-pool {:handler :cleanup-timeline
                                            :arguments {:room-id room-id}})
   {:db (-> db
            (update :timeline-subs dissoc room-id)
            (update :timeline-data dissoc room-id))}))


(re-frame/reg-sub
 :timeline/pinned-events
 (fn [db [_ room-id]]
   (get-in db [:timeline-data room-id :pins] [])))

(re-frame/reg-event-fx
 :sdk/back-paginate
 (fn [{:keys [db]} [_ room-id]]
   (let [loading-back?    (get-in db [:timeline/loading-more? room-id])]
     (if (and room-id (not loading-back?))
       (do
         (log/info "Triggering Worker Back Paginate for" room-id)
         (go
           (let [res (<! (main/do-with-pool! @state/!engine-pool
                                             {:handler :paginate-timeline
                                              :arguments {:room-id room-id :direction :back :amount 25}}))]
             (re-frame/dispatch [:sdk/pagination-complete room-id])))
         {:db (assoc-in db [:timeline/loading-more? room-id] true)})
       {}))))

(re-frame/reg-event-fx
 :sdk/forward-paginate
 (fn [{:keys [db]} [_ room-id]]
   (let [loading-forward? (get-in db [:timeline/loading-forward? room-id])]
     (if (and room-id (not loading-forward?))
       (do
         (log/info "Triggering Worker Forward Paginate for" room-id)
         (go
           (let [res (<! (main/do-with-pool! @state/!engine-pool
                                             {:handler :paginate-timeline
                                              :arguments {:room-id room-id :direction :forward :amount 25}}))]
             (re-frame/dispatch [:sdk/forward-pagination-complete room-id])
             ))
         {:db (assoc-in db [:timeline/loading-forward? room-id] true)})
       {}))))

(re-frame/reg-sub
 :timeline/back-dead?
 (fn [db [_ room-id]]
   (get-in db [:timeline/back-dead? room-id] false)))

(re-frame/reg-event-db
 :timeline/set-loading
 (fn [db [_ room-id loading?]]
   (assoc-in db [:timeline/loading-more? room-id] loading?)))

(re-frame/reg-event-db
 :sdk/pagination-complete
 (fn [db [_ room-id]]
   (-> db
       (assoc-in [:timeline/loading-more? room-id] false)
       (assoc-in [:timeline/ui-metadata room-id :pending-anchor] nil))))

(re-frame/reg-event-db
 :sdk/forward-pagination-complete
 (fn [db [_ room-id]]
   (assoc-in db [:timeline/loading-forward? room-id] false)))


(re-frame/reg-event-db
 :sdk/update-pagination-status
 (fn [db [_ room-id status]]
   (assoc-in db [:timeline-pagination room-id] status)))

(re-frame/reg-event-fx
 :room/pretty-jump
 (fn [{:keys [db]} [_ room-id event-id]]
   {:db (assoc-in db [:timeline/ui-state room-id :animating-jump?] true)
    :dispatch-later [{:ms 300
                      :dispatch [:room/execute-deep-jump room-id event-id]}]}))

(re-frame/reg-event-fx
 :room/execute-deep-jump
 (fn [{:keys [db]} [_ room-id event-id]]
   {:db (assoc-in db [:timeline/jump-target-id room-id] event-id)
    :dispatch-n [[:sdk/cleanup-timeline room-id]
                 [:room/boot-focused-timeline room-id event-id]]}))

(re-frame/reg-event-fx
 :room/jump-to-live-bottom
 (fn [{:keys [db]} [_ room-id]]
   {:db (-> db
            (assoc-in [:timeline/ui-state room-id :animating-jump?] true)
            (update :timeline/jump-target-id dissoc room-id))
    :dispatch-later [{:ms 300
                      :dispatch [:room/execute-return-to-live room-id]}]}))
(re-frame/reg-event-fx
 :room/execute-return-to-live
 (fn [{:keys [db]} [_ room-id]]
   {:dispatch-n [[:sdk/cleanup-timeline room-id]
                 [:sdk/boot-timeline room-id]]}))

(re-frame/reg-event-fx
 :room/jump-to-present
 (fn [{:keys [db]} [_ room-id]]
   (main/do-with-pool! @state/!engine-pool {:handler :cleanup-timeline
                                            :arguments {:room-id room-id}})
   {:db (-> db
            (update-in [:timeline-subs room-id] dissoc :focused :focused-handle)
            (assoc-in [:timeline-data room-id :focused] [])
            (assoc-in [:timeline/ui-state room-id :animating-jump?] true))
    :dispatch-later [{:ms 50
                      :dispatch [:room/jump-to-live-bottom room-id]}]}))

(re-frame/reg-sub
 :timeline/ui-state
 (fn [db [_ room-id]]
   (get-in db [:timeline/ui-state room-id] {})))

(re-frame/reg-sub
 :timeline/jump-target-id
 (fn [db [_ room-id]]
   (get-in db [:timeline/jump-target-id room-id])))

(re-frame/reg-sub
 :timeline/loading-forward?
 (fn [db [_ room-id]]
   (get-in db [:timeline/loading-forward? room-id] false)))

(re-frame/reg-sub
 :timeline/ui-metadata
 (fn [db [_ room-id]]
   (get-in db [:timeline/ui-metadata room-id] {})))

(re-frame/reg-sub
 :timeline/loading-more?
 (fn [db [_ room-id]]
   (get-in db [:timeline/loading-more? room-id] false)))

(re-frame/reg-sub
 :room/is-focused?
 (fn [db [_ room-id]]
   (some? (get-in db [:timeline/jump-target-id room-id]))))


(defn followers-indicator [active-room]
  (let [reader-ids  @(re-frame/subscribe [:timeline/latest-readers])
        members-map @(re-frame/subscribe [:room/members-map active-room])
        has-readers? (boolean (seq reader-ids))
        names (when has-readers?
                (map (fn [id] (or (:display-name (get members-map id)) id))
                     reader-ids))]
    [:div.followers-indicator
     {:class (when has-readers? "is-visible")}
     (when has-readers?
       [icons/double-check {:width "14px" :height "14px"}])
     [:span.text
      (if has-readers?
        (format-readers names)
        "\u00A0")]
     ]))

(re-frame/reg-event-db
 :sdk/clear-stale-typing
 (fn [db [_ room-id]]
   (let [data (get-in db [:typing-users room-id])]
     (if (and data (> (- (js/Date.now) (:last-update data)) 6000))
       (update db :typing-users dissoc room-id)
       db))))

(re-frame/reg-event-fx
 :sdk/update-typing-users
 (fn [{:keys [db]} [_ room-id user-ids]]
   (let [users (js->clj user-ids)]
     (if (empty? users)
       {:db (update db :typing-users dissoc room-id)}
       {:db (assoc-in db [:typing-users room-id]
                      {:users users
                       :last-update (js/Date.now)})
        :dispatch-later [{:ms 7000
                          :dispatch [:sdk/clear-stale-typing room-id]}]}))))

(re-frame/reg-sub
 :room/typing-users
 (fn [db [_ room-id]]
   (let [data (get-in db [:typing-users room-id])]
     (if (and data
              (< (- (js/Date.now) (:last-update data)) 6000))
       (:users data)
       []))))


(re-frame/reg-sub
 :timeline/latest-readers
 (fn [[_ room-id _]]
   [(re-frame/subscribe [:timeline/current-events room-id])
    (re-frame/subscribe [:sdk/profile])])
 (fn [[events profile] _]
   (let [my-id         (:user-id profile)
         actual-events (filter #(= (:type %) :event) events)
         latest-event  (last actual-events)
         read-by       (or (:read-by latest-event) [])
         others        (remove #(= % my-id) read-by)]
     others)))


(defn status-indicator [active-room]
  (let [tr            @(re-frame/subscribe [:i18n/tr])
        typing-info   @(re-frame/subscribe [:room/typing-users active-room])
        reader-ids    @(re-frame/subscribe [:timeline/latest-readers active-room])
        members-map   @(re-frame/subscribe [:room/members-map active-room])
        profile       @(re-frame/subscribe [:sdk/profile])
        my-id         (:user-id profile)
        typing-ids    (if (map? typing-info) (:users typing-info) typing-info)
        others-typing (filterv #(not= % my-id) (or typing-ids []))
        get-name      #(or (:display-name (get members-map %)) %)
        typing-names  (map get-name others-typing)
        reader-names  (map get-name reader-ids)

        has-typists?  (not-empty typing-names)
        has-readers?  (not-empty reader-names)
        is-visible?   (or has-typists? has-readers?)]
    [:div.followers-indicator
     {:class (when is-visible? "is-visible")
      :key (str active-room "-status-bar")}
     (cond
       has-typists?
       [:div.status-content {:key "typing"}
        [icons/typing-dots {:width "14px" :height "14px" :style {:color "var(--cp-text-muted)"}}]
        [:span.text (get-status-string tr :typing typing-names)]]
       has-readers?
       [:div.status-content {:key "readers"}
        [icons/double-check {:width "14px" :height "14px"}]
        [:span.text (get-status-string tr :reading reader-names)]]
       :else
       [:span.text {:key "empty"} "\u00A0"])]))

(defn timeline-estimate-fn [msg width !prepared-cache !measured-atom theme]
  (if (= (:id msg) "read-marker")
    (:virtual-divider-h theme 49)
    (calc-item-height msg width !prepared-cache theme)))

(def default-metrics {:font "16px sans-serif" :line-height 22.8})

(defn pretext-timeline [room-id]
  (let [on-load-older   #(re-frame/dispatch [:sdk/back-paginate room-id])
        on-load-newer   #(re-frame/dispatch [:sdk/forward-paginate room-id])
        on-jump-live    #(re-frame/dispatch [:room/jump-to-live-bottom room-id])
        render-empty    (fn [] [timeline-empty-state room-id])
        render-loading  (fn [] [timeline-loading-overlay])
        render-sticks   (fn [ruler-ref-fn] [timeline-measuring-sticks ruler-ref-fn])
        render-jump     (fn [do-jump! is-focused?] [timeline-jump-button do-jump! is-focused?])]
    (fn [room-id]
      (let [events           @(re-frame/subscribe [:timeline/current-events room-id])
            events-map       @(re-frame/subscribe [:timeline/events-map room-id])
            loading?         @(re-frame/subscribe [:timeline/loading-more? room-id])
            loading-forward? @(re-frame/subscribe [:timeline/loading-forward? room-id])
            back-dead?       @(re-frame/subscribe [:timeline/back-dead? room-id])
            jump-target      @(re-frame/subscribe [:timeline/jump-target-id room-id])
            focus-mode?      @(re-frame/subscribe [:room/is-focused? room-id])
            hs-url           @(re-frame/subscribe [:sdk/homeserver-url])
            is-mobile?       @(re-frame/subscribe [:ui/mobile?])
            my-profile       @(re-frame/subscribe [:sdk/profile])
            my-id            (:user-id my-profile)
            tr               @(re-frame/subscribe [:i18n/tr])
            render-item      (fn [layout-node]
                              ^{:key (:id layout-node)}
                               [event-tile (:id layout-node) hs-url is-mobile? my-id tr])]
        [virtualized-list
         {:items                   events
          :items-map               events-map
          :loading-older?          loading?
          :loading-newer?          loading-forward?
          :older-dead?             back-dead?
          :jump-target-id          jump-target
          :focus-mode?             focus-mode?
          :on-load-older           on-load-older
          :on-load-newer           on-load-newer
          :on-jump-live            on-jump-live
          :estimate-fn             timeline-estimate-fn
          :extract-metrics-fn      extract-timeline-metrics
          :default-theme-metrics   default-metrics
          :wrapper-class           "timeline-wrapper"
          :scroll-container-class  "timeline-messages"
          :render-item             render-item
          :render-empty-state      render-empty
          :render-jump-button      render-jump
          :render-loading-overlay  render-loading
          :render-measuring-sticks render-sticks}]))))

(defn timeline [& {:keys [compact? hide-header?]}]
  (let [active-id    @(re-frame/subscribe [:rooms/active-id])
        room-meta    @(re-frame/subscribe [:rooms/active-metadata])
        display-name (when active-id (or (when room-meta (.-name room-meta)) active-id))]
    [:div.timeline-container
     (when-not hide-header?
       [room-header {:display-name display-name
                     :compact?     compact?
                     :active-id    active-id}])

     (if-not active-id
       [timeline-empty-state active-id]
       [:<>
        [pretext-timeline active-id]
        [message-input]
        [status-indicator active-id]
        ])]))
