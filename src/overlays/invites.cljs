(ns overlays.invites
  (:require
   [utils.global-ui :refer [avatar handle-list-navigation selectable-list]]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [clojure.string :as str]
   [utils.macros :refer [defui]]
   [overlays.base :refer [modal-component popover-component]]))

(defui invite-menu-content [{:keys [target-room-id]}]
  (let [tr        @(re-frame/subscribe [:i18n/tr])
        rooms-map @(re-frame/subscribe [:rooms/unfiltered-indexed-map])
        dm-rooms  (->> (vals rooms-map)
                       (filter #(or (:isDirect %) (:is-direct? %) (:is-direct %))))
        close-fn  #(re-frame/dispatch [:ui/close-modal])]
    (r/with-let [query          (r/atom "")
                 selected-index (r/atom 0)]
      (let [lower-query   (str/lower-case @query)
            is-matrix-id? (re-matches #"^@.+:.+$" @query)
            filtered-dms  (if (empty? @query)
                            (take 15 dm-rooms)
                            (->> dm-rooms
                                 (filter #(str/includes?
                                           (str/lower-case (or (:name %) "unknown"))
                                           lower-query))
                                 (take 15)))
            final-items   (if is-matrix-id?
                            (vec (cons {:is-raw-id? true :user-id @query} filtered-dms))
                            (vec filtered-dms))]
        [:div.quick-switcher-container
         [:input.form-input.quick-switcher-input
          {:type "text"
           :auto-focus true
           :placeholder (tr [:invites/placeholder])
           :value @query
           :on-change (fn [e]
                        (reset! query (.. e -target -value))
                        (reset! selected-index 0))
           :on-key-down (fn [e]
                          (when (= (.-key e) "Escape")
                            (close-fn))
                          (handle-list-navigation
                           e final-items @selected-index
                           #(reset! selected-index %)
                           #(let [target-user (if (:is-raw-id? %)
                                                (:user-id %)
                                                (:id %))]
                              nil)))}]
         [:div.quick-switcher-results
          [selectable-list
           {:items          final-items
            :selected-index @selected-index
            :empty-text     (tr [:invites/empty])
            :item-class     "quick-switcher-item"
            :key-fn         #(or (:user-id %) (:id %) (:roomId %))
            :on-highlight   #(reset! selected-index %)
            :on-select      (fn [item]
                              (if (:is-raw-id? item)
                                (re-frame/dispatch [:rooms/invite-user target-room-id (:user-id item)])
                                (re-frame/dispatch [:rooms/invite-from-dm target-room-id (or (:id item) (:roomId item))]))
                              (close-fn))
            :render-item    (fn [item _]
                              (if (:is-raw-id? item)
                                [:div.invite-user-row
                                 [avatar {:id    (:user-id item)
                                          :name  "?"
                                          :size  28
                                          :shape :rounded}]
                                 [:div.invite-user-info
                                  [:span.name.raw-id "Invite user by ID"]
                                  [:span.sub-name (:user-id item)]]]
                                (let [room-id      (or (:id item) (:roomId item))
                                      members      @(re-frame/subscribe [:room/members-map room-id])
                                      profile      @(re-frame/subscribe [:sdk/profile])
                                      my-id        (:user-id profile)
                                      other        (when (seq members)
                                                     (->> (vals members)
                                                          (remove #(= (:user-id %) my-id))
                                                          first))
                                      final-avatar (or (:avatarUrl item) (:avatar item) (:avatar-url other))
                                      final-name   (if (and other (or (nil? (:name item)) (= (:name item) (tr [:invites/loading]))))
                                                     (:display-name other)
                                                     (:name item))]
                                  [:div.invite-user-row
                                   [avatar {:id    room-id
                                            :name  (or final-name "?")
                                            :url   final-avatar
                                            :size  28
                                            :shape :rounded}]
                                   [:div.invite-user-info
                                    [:span.name final-name]
                                    (when other
                                      [:span.sub-name (:user-id other)])]])))}]]]))))

(defmethod modal-component :invite-user [_]
  invite-menu-content)