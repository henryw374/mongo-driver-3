(ns mongo-driver-3.collection
  (:refer-clojure :exclude [find empty? drop])
  (:import (clojure.lang Ratio Keyword Named IPersistentMap)
           (com.mongodb ReadConcern ReadPreference WriteConcern MongoNamespace)
           (com.mongodb.client MongoDatabase MongoCollection TransactionBody)
           (com.mongodb.client.model InsertOneOptions InsertManyOptions DeleteOptions FindOneAndUpdateOptions ReturnDocument FindOneAndReplaceOptions CountOptions CreateCollectionOptions RenameCollectionOptions IndexOptions IndexModel UpdateOptions ReplaceOptions)
           (java.util List Collection)
           (java.util.concurrent TimeUnit)
           (org.bson Document)
           (org.bson.types Decimal128)))

;;; Conversions

(defprotocol ConvertToDocument
  (^Document document [input] "Convert some clojure to a Mongo Document"))

(extend-protocol ConvertToDocument
  nil
  (document [_]
    nil)

  Ratio
  (document [^Ratio input]
    (double input))

  Keyword
  (document [^Keyword input]
    (.getName input))

  Named
  (document [^Named input]
    (.getName input))

  IPersistentMap
  (document [^IPersistentMap input]
    (let [o (Document.)]
      (doseq [[k v] input]
        (.append o (document k) (document v)))
      o))

  Collection
  (document [^Collection input]
    (map document input))

  Object
  (document [input]
    input))

(defprotocol ConvertFromDocument
  (from-document [input keywordize?] "Converts given Document to Clojure"))

(extend-protocol ConvertFromDocument
  nil
  (from-document [input _]
    input)

  Object
  (from-document [input _] input)

  Decimal128
  (from-document [^Decimal128 input _]
    (.bigDecimalValue input))

  List
  (from-document [^List input keywordize?]
    (vec (map #(from-document % keywordize?) input)))

  Document
  (from-document [^Document input keywordize?]
    (reduce (if keywordize?
              (fn [m ^String k]
                (assoc m (keyword k) (from-document (.get input k) true)))
              (fn [m ^String k]
                (assoc m k (from-document (.get input k) false))))
            {} (.keySet input))))


;;; Collection


(def kw->ReadConcern
  {:available    (ReadConcern/AVAILABLE)
   :default      (ReadConcern/DEFAULT)
   :linearizable (ReadConcern/LINEARIZABLE)
   :local        (ReadConcern/LOCAL)
   :majority     (ReadConcern/MAJORITY)
   :snapshot     (ReadConcern/SNAPSHOT)})

(defn ->ReadConcern
  "Coerce `rc` into a ReadConcern if not nil.

  Accepts a ReadConcern or kw corresponding to one:
   [:available, :default, :linearizable, :local, :majority, :snapshot]

  Invalid values will throw an exception."
  [rc]
  (when rc
    (if (instance? ReadConcern rc)
      rc
      (or (kw->ReadConcern rc) (throw (IllegalArgumentException.
                                       (str "No match for read concern of " (name rc))))))))

(defn ->ReadPreference
  "Coerce `rp` into a ReadPreference if not nil.

  Accepts a ReadPreference or a kw corresponding to one:
    [:primary, :primaryPreferred, :secondary, :secondaryPreferred, :nearest]

  Invalid values will throw an exception."
  [rp]
  (when rp
    (if (instance? ReadPreference rp)
      rp
      (ReadPreference/valueOf (name rp)))))

(defn ->WriteConcern
  "Coerce write-concern related options to a WriteConcern.

  Accepts an options map:

    :write-concern A WriteConcern or kw corresponding to one:
      [:acknowledged, :journaled, :majority, :unacknowledged, :w1, :w2, :w3],
      defaulting to :acknowledged, if some invalid option is provided.
    :write-concern/w an int >= 0, controlling the number of replicas to acknowledge
    :write-concern/w-timeout-ms How long to wait for secondaries to acknowledge before failing,
      in milliseconds (0 means indefinite).
    :write-concern/journal? If true, block until write operations have been committed to the journal."
  [{:keys [write-concern write-concern/w write-concern/w-timeout-ms write-concern/journal?]}]
  (when (some some? [write-concern w w-timeout-ms journal?])
    (let [wc (when write-concern
               (if (instance? WriteConcern write-concern)
                 write-concern
                 (WriteConcern/valueOf (name write-concern))))]
      (-> (or wc (WriteConcern/ACKNOWLEDGED))
          (#(if w (.withW % w) %))
          (#(if w-timeout-ms (.withWTimeout % w-timeout-ms (TimeUnit/MILLISECONDS)) %))
          (#(if (some? journal?) (.withJournal % journal?) %))))))

(defn collection
  "Coerces `coll` to a MongoCollection with some options.

  `db`   is a MongoDatabase
  `coll` is a collection name or a MongoCollection. This is to provide flexibility in the use of
    higher-level fns (e.g. `find-maps`), either in reuse of instances or in some more complex
    configuration we do not directly support.

  Accepts an options map:
    :read-preference
    :read-concern
    :write-concern
    :write-concern/w
    :write-concern/w-timeout-ms
    :write-concern/journal?

  See respective coercion functions for details (->ReadPreference, ->ReadConcern, ->WriteConcern)."
  ([^MongoDatabase db coll]
   (collection db coll {}))
  ([^MongoDatabase db coll opts]
   (let [coll' (if (instance? MongoCollection coll) coll (.getCollection db coll))
         {:keys [read-concern read-preference]} opts]
     (-> coll'
         (#(if-let [rp (->ReadPreference read-preference)] (.withReadPreference % rp) %))
         (#(if-let [rc (->ReadConcern read-concern)] (.withReadConcern % rc) %))
         (#(if-let [wc (->WriteConcern opts)] (.withWriteConcern % wc) %))))))

;;; CRUD functions

(defn aggregate
  "Aggregates documents according to the specified aggregation pipeline and returns an AggregateIterable.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection namee
  - `q` is a map representing a query.
  - `opts` (optional), a map of:
    - `:allow-disk-use?` whether to allow writing temporary files
    - `:batch-size` Documents to return per batch, e.g. 1
    - `:bypass-document-validation?` Boolean
    - `:keywordize?` keywordize the keys of return results, default: true
    - `:raw?` return the mongo AggregateIterable directly instead of processing into a seq, default: false
    - `:session` a ClientSession"
  ([^MongoDatabase db coll pipeline]
   (aggregate db coll pipeline {}))
  ([^MongoDatabase db coll pipeline opts]
   (let [{:keys [session allow-disk-use? batch-size bypass-document-validation? keywordize? raw?] :or {keywordize? true raw? false}} opts
         it (-> (if session
                  (.aggregate (collection db coll opts) session (document pipeline))
                  (.aggregate (collection db coll opts) (document pipeline)))
                (#(if (some? allow-disk-use?) (.allowDiskUse % allow-disk-use?) %))
                (#(if batch-size (.batchSize % batch-size) %))
                (#(if (some? bypass-document-validation?) (.bypassDocumentValidation % bypass-document-validation?) %)))]

     (if-not raw?
       (map (fn [x] (from-document x keywordize?)) (seq it))
       it))))

(defn ->CountOptions
  "Coerce options map into CountOptions. See `count-documents` for usage."
  [{:keys [count-options hint limit max-time-ms skip]}]
  (let [opts (or count-options (CountOptions.))]
    (when hint (.hint opts (document hint)))
    (when limit (.limit opts limit))
    (when max-time-ms (.maxTime opts max-time-ms (TimeUnit/MILLISECONDS)))
    (when skip (.skip opts skip))

    opts))

(defn count-documents
  "Count documents in a collection, optionally matching a filter query `q`.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `q` is a map representing a query.
  - `opts` (optional), a map of:
    - `:hint` an index name (string) hint or specification (map)
    - `:max-time-ms` max amount of time to allow the query to run, in milliseconds
    - `:skip` number of documents to skip before counting
    - `:limit` max number of documents to count
    - `:count-options` a CountOptions, for configuring directly. If specified, any
       other [preceding] query options will be applied to it.
    - `:session` a ClientSession

  Additionally takes options specified in `collection`."
  ([^MongoDatabase db coll]
   (.countDocuments (collection db coll {})))
  ([^MongoDatabase db coll q]
   (count-documents db coll q {}))
  ([^MongoDatabase db coll q opts]
   (let [opts' (->CountOptions opts)]
     (if-let [session (:session opts)]
       (.countDocuments (collection db coll opts) session (document q) opts')
       (.countDocuments (collection db coll opts) (document q) opts')))))

(defn ->DeleteOptions
  "Coerce options map into DeleteOptions. See `delete-one` and `delete-many` for usage."
  [{:keys [delete-options]}]
  (let [opts (or delete-options (DeleteOptions.))]
    opts))

(defn delete-one
  "Deletes a single document from a collection and returns a DeleteResult.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `q` is a map representing a query.
  - `opts` (optional), a map of:
    - `:delete-options` A DeleteOptions for configuring directly.
    - `:session` A ClientSession

  Additionally takes options specified in `collection`"
  ([^MongoDatabase db coll q]
   (delete-one db coll q {}))
  ([^MongoDatabase db coll q opts]
   (if-let [session (:session opts)]
     (.deleteOne (collection db coll opts) session (document q) (->DeleteOptions opts))
     (.deleteOne (collection db coll opts) (document q) (->DeleteOptions opts)))))

(defn delete-many
  "Deletes multiple documents from a collection and returns a DeleteResult.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `q` is a map representing a query.
  - `opts` (optional), a map of:
    - `:delete-options` A DeleteOptions for configuring directly.
    - `:session` A ClientSession

  Additionally takes options specified in `collection`"
  ([^MongoDatabase db coll q]
   (delete-many db coll q {}))
  ([^MongoDatabase db coll q opts]
   (if-let [session (:session opts)]
     (.deleteMany (collection db coll opts) session (document q) (->DeleteOptions opts))
     (.deleteMany (collection db coll opts) (document q) (->DeleteOptions opts)))))

(defn find
  "Finds documents and returns a seq of maps, unless configured otherwise.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `q` is a map representing a query.
  - `opts` (optional), a map of:
    - `:limit` Max number of documents to return, e.g. 1
    - `:skip` Number of documents to skip, e.g. 1
    - `:sort` document representing sort order, e.g. {:timestamp -1}
    - `:projection` document representing fields to return, e.g. {:_id 0}
    - `:keywordize?` keywordize the keys of return results, default: true
    - `:raw?` return the mongo FindIterable directly instead of processing into a seq, default: false
    - `:session` a ClientSession

  Additionally takes options specified in `collection`."
  ([^MongoDatabase db coll q]
   (find db coll q {}))
  ([^MongoDatabase db coll q opts]
   (let [{:keys [limit skip sort projection session keywordize? raw?] :or {keywordize? true raw? false}} opts]
     (let [it (-> (if session
                    (.find (collection db coll opts) session (document q))
                    (.find (collection db coll opts) (document q)))
                  (#(if limit (.limit % limit) %))
                  (#(if skip (.skip % skip) %))
                  (#(if sort (.sort % (document sort)) %))
                  (#(if projection (.projection % (document projection)) %)))]

       (if-not raw?
         (map (fn [x] (from-document x keywordize?)) (seq it))
         it)))))

(defn find-one
  "Finds a single document and returns it as a clojure map, or nil if not found.

  Takes the same options as `find`."
  ([^MongoDatabase db coll q]
   (find-one db coll q {}))
  ([^MongoDatabase db coll q opts]
   (first (find db coll q (assoc opts :limit 1 :raw? false)))))

(defn ->FindOneAndUpdateOptions
  "Coerce options map into FindOneAndUpdateOptions. See `find-one-and-update` for usage."
  [{:keys [find-one-and-update-options upsert? return-new? sort projection]}]
  (let [opts (or find-one-and-update-options (FindOneAndUpdateOptions.))]
    (when (some? upsert?) (.upsert opts upsert?))
    (when return-new? (.returnDocument opts (ReturnDocument/AFTER)))
    (when sort (.sort opts (document sort)))
    (when projection (.projection opts (document projection)))

    opts))

(defn find-one-and-update
  "Atomically find a document (at most one) and modify it.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `q` is a map representing a query.
  - `update` is a map representing an update. The update to apply must include only update operators.
  - `opts` (optional), a map of:
    - `:upsert?` whether to insert a new document if nothing is found, default: false
    - `:return-new?` whether to return the document after update (insead of its state before the update), default: false
    - `:sort` map representing sort order, e.g. {:timestamp -1}
    - `:projection` map representing fields to return, e.g. {:_id 0}
    - `:find-one-and-update-options` A FindOneAndUpdateOptions for configuring directly. If specified,
    any other [preceding] query options will be applied to it.
    - `:keywordize?` keywordize the keys of return results, default: true
    - `:session` a ClientSession

  Additionally takes options specified in `collection`."
  ([^MongoDatabase db coll q update]
   (find-one-and-update db coll q update {}))
  ([^MongoDatabase db coll q update opts]
   (let [{:keys [keywordize? session] :or {keywordize? true}} opts
         opts' (->FindOneAndUpdateOptions opts)]
     (-> (if session
           (.findOneAndUpdate (collection db coll opts) session (document q) (document update) opts')
           (.findOneAndUpdate (collection db coll opts) (document q) (document update) opts'))
         (from-document keywordize?)))))

(defn ->FindOneAndReplaceOptions
  "Coerce options map into FindOneAndReplaceOptions. See `find-one-and-replace` for usage."
  [{:keys [find-one-and-replace-options upsert? return-new? sort projection]}]
  (let [opts (or find-one-and-replace-options (FindOneAndReplaceOptions.))]
    (when (some? upsert?) (.upsert opts upsert?))
    (when return-new? (.returnDocument opts (ReturnDocument/AFTER)))
    (when sort (.sort opts (document sort)))
    (when projection (.projection opts (document projection)))

    opts))

(defn find-one-and-replace
  "Atomically find a document (at most one) and replace it.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `q` is a map representing a query.
  - `doc` is a new document to add.
  - `opts` (optional), a map of:
    - `:upsert?` whether to insert a new document if nothing is found, default: false
    - `:return-new?` whether to return the document after update (insead of its state before the update), default: false
    - `:sort` map representing sort order, e.g. {:timestamp -1}
    - `:projection` map representing fields to return, e.g. {:_id 0}
    - `:find-one-and-replace-options` A FindOneAndReplaceOptions for configuring directly. If specified,
    any other [preceding] query options will be applied to it.
    - `:keywordize?` keywordize the keys of return results, default: true
    - `:session` a ClientSession

  Additionally takes options specified in `collection`."
  ([^MongoDatabase db coll q doc]
   (find-one-and-replace db coll q doc {}))
  ([^MongoDatabase db coll q doc opts]
   (let [{:keys [keywordize? session] :or {keywordize? true}} opts
         opts' (->FindOneAndReplaceOptions opts)]
     (-> (if session
           (.findOneAndReplace (collection db coll opts) session (document q) (document doc) opts')
           (.findOneAndReplace (collection db coll opts) (document q) (document doc) opts'))
         (from-document keywordize?)))))

(defn ->InsertOneOptions
  "Coerce options map into InsertOneOptions. See `insert-one` for usage."
  [{:keys [insert-one-options bypass-document-validation?]}]
  (let [opts (or insert-one-options (InsertOneOptions.))]
    (when (some? bypass-document-validation?) (.bypassDocumentValidation opts bypass-document-validation?))

    opts))

(defn insert-one
  "Inserts a single document into a collection, and returns nil.
  If the document does not have an _id field, it will be auto-generated by the underlying driver.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `doc` is a map to insert.
  - `opts` (optional), a map of:
    - `:bypass-document-validation?` Boolean
    - `:insert-one-options` An InsertOneOptions for configuring directly. If specified,
       any other [preceding] query options will be applied to it.
    - `:session` A ClientSession

  Additionally takes options specified in `collection`."
  ([^MongoDatabase db coll doc]
   (insert-one db coll doc {}))
  ([^MongoDatabase db coll doc opts]
   (let [opts' (->InsertOneOptions opts)]
     (if-let [session (:session opts)]
       (.insertOne (collection db coll opts) session (document doc) opts')
       (.insertOne (collection db coll opts) (document doc) opts')))))

(defn ->InsertManyOptions
  "Coerce options map into InsertManyOptions. See `insert-many` for usage."
  [{:keys [insert-many-options bypass-document-validation? ordered?]}]
  (let [opts (or insert-many-options (InsertManyOptions.))]
    (when (some? bypass-document-validation?) (.bypassDocumentValidation opts bypass-document-validation?))
    (when (some? ordered?) (.ordered opts ordered?))

    opts))

(defn insert-many
  "Inserts multiple documents into a collection.
  If a document does not have an _id field, it will be auto-generated by the underlying driver.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `docs` is a collection of maps to insert
  - `opts` (optional), a map of:
    - `:bypass-document-validation?` Boolean
    - `:ordered?` Boolean whether serve should insert documents in order provided (default true)
    - `:insert-many-options` An InsertManyOptions for configuring directly. If specified,
      any other [preceding] query options will be applied to it.
    - `:session` A ClientSession

  Additionally takes options specified in `collection`"
  ([^MongoDatabase db coll docs]
   (insert-many db coll docs {}))
  ([^MongoDatabase db coll docs opts]
   (let [opts' (->InsertManyOptions opts)]
     (if-let [session (:session opts)]
       (.insertMany (collection db coll opts) session (map document docs) opts')
       (.insertMany (collection db coll opts) (map document docs) opts')))))

(defn ->ReplaceOptions
  "Coerce options map into ReplaceOptions. See `replace-one` and `replace-many` for usage."
  [{:keys [replace-options upsert? bypass-document-validation?]}]
  (let [opts (or replace-options (ReplaceOptions.))]
    (when (some? upsert?) (.upsert opts upsert?))
    (when (some? bypass-document-validation?) (.bypassDocumentValidation opts bypass-document-validation?))

    opts))

(defn replace-one
  "Replace a single document in a collection and returns an UpdateResult.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `q` is a map representing a query.
  - `doc` is a new document to add.
  - `opts` (optional), a map of:
    - `:upsert?` whether to insert a new document if nothing is found, default: false
    - `:bypass-document-validation?` Boolean
    - `:replace-options` A ReplaceOptions for configuring directly. If specified,
    any other [preceding[ query options will be applied to it.
    - `:session` a ClientSession

  Additionally takes options specified in `collection`"
  ([^MongoDatabase db coll q doc]
   (find-one-and-replace db coll q doc {}))
  ([^MongoDatabase db coll q doc opts]
   (if-let [session (:session opts)]
     (.replaceOne (collection db coll opts) session (document q) (document doc) (->ReplaceOptions opts))
     (.replaceOne (collection db coll opts) (document q) (document doc) (->ReplaceOptions opts)))))

(defn ->UpdateOptions
  "Coerce options map into UpdateOptions. See `update-one` and `update-many` for usage."
  [{:keys [update-options upsert? bypass-document-validation?]}]
  (let [opts (or update-options (UpdateOptions.))]
    (when (some? upsert?) (.upsert opts upsert?))
    (when (some? bypass-document-validation?) (.bypassDocumentValidation opts bypass-document-validation?))

    opts))

(defn update-one
  "Updates a single document in a collection and returns an UpdateResult.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `q` is a map representing a query.
  - `update` is a map representing an update. The update to apply must include only update operators.
  - `opts` (optional), a map of:
    - `:upsert?` whether to insert a new document if nothing is found, default: false
    - `:bypass-document-validation?` Boolean
    - `:update-options` An UpdateOptions for configuring directly. If specified,
    any other [preceding[ query options will be applied to it.
    - `:session` a ClientSession

  Additionally takes options specified in `collection`"
  ([^MongoDatabase db coll q update]
   (update-one db coll q update {}))
  ([^MongoDatabase db coll q update opts]
   (if-let [session (:session opts)]
     (.updateOne (collection db coll opts) session (document q) (document update) (->UpdateOptions opts))
     (.updateOne (collection db coll opts) (document q) (document update) (->UpdateOptions opts)))))

(defn update-many
  "Updates many documents in a collection and returns an UpdateResult.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `q` is a map representing a query.
  - `update` is a map representing an update. The update to apply must include only update operators.
  - `opts` (optional), a map of:
    - `:upsert?` whether to insert a new document if nothing is found, default: false
    - `:bypass-document-validation?` Boolean
    - `:update-options` An UpdateOptions for configuring directly. If specified,
    any other [preceding[ query options will be applied to it.
    - `:session` a ClientSession

  Additionally takes options specified in `collection`"
  ([^MongoDatabase db coll q update]
   (update-many db coll q {}))
  ([^MongoDatabase db coll q update opts]
   (if-let [session (:session opts)]
     (.updateMany (collection db coll opts) session (document q) (document update) (->UpdateOptions opts))
     (.updateMany (collection db coll opts) (document q) (document update) (->UpdateOptions opts)))))

;;; Admin functions

(defn ->CreateCollectionOptions
  "Coerce options map into CreateCollectionOptions. See `create` usage."
  [{:keys [create-collection-options capped? max-documents max-size-bytes]}]
  (let [opts (or create-collection-options (CreateCollectionOptions.))]
    (when (some? capped?) (.capped opts capped?))
    (when max-documents (.maxDocuments opts max-documents))
    (when max-size-bytes (.sizeInBytes opts max-size-bytes))

    opts))

(defn create
  "Creates a collection

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `opts` (optional), a map of:
    - `:capped?` Boolean whether to create a capped collection
    - `:max-documents` max documents for a capped collection
    - `:max-size-bytes` max collection size in bytes for a capped collection
    - `:create-collection-options` A CreateCollectionOptions for configuring directly. If specified,
    any other [preceding] query options will be applied to it"
  ([^MongoDatabase db coll]
   (create db coll {}))
  ([^MongoDatabase db coll opts]
   (let [opts' (->CreateCollectionOptions opts)]
     (.createCollection db coll opts'))))

(defn ->RenameCollectionOptions
  "Coerce options map into RenameCollectionOptions. See `rename` usage."
  [{:keys [rename-collection-options drop-target?]}]
  (let [opts (or rename-collection-options (RenameCollectionOptions.))]
    (when (some? drop-target?) (.dropTarget opts drop-target?))

    opts))

(defn rename
  "Renames `coll` to `new-coll` in the same DB.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `new-coll` is the target collection name
  - `opts` (optional), a map of:
    - `:drop-target?` Boolean drop tne target collection if it exists. Default: false
    - `:rename-collection-options` A RenameCollectionOptions for configuring directly. If specified,
    any other [preceding] query options will be applied to it"
  ([^MongoDatabase db coll new-coll]
   (rename db coll new-coll {}))
  ([^MongoDatabase db coll new-coll opts]
   (let [opts' (->RenameCollectionOptions opts)]

     (.renameCollection (collection db coll opts)
                        (MongoNamespace. (.getName db) new-coll)
                        opts'))))

(defn drop
  "Drops a collection from a database."
  [^MongoDatabase db coll]
  (.drop (collection db coll)))

(defn ->IndexOptions
  "Coerces an options map into an IndexOptions.

  See `create-index` for usage"
  [{:keys [index-options name sparse? unique?]}]
  (let [opts (or index-options (IndexOptions.))]
    (when name (.name opts name))
    (when (some? sparse?) (.sparse opts sparse?))
    (when (some? unique?) (.unique opts unique?))

    opts))

(defn create-index
  "Creates an index

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `keys` is a document representing index keys, e.g. {:a 1}
  - `opts` (optional), a map of:
    - `:name`
    - `:sparse?`
    - `:unique?`
    - `:index-options` An IndexOptions for configuring directly. If specified,
    any other [preceding] query options will be applied to it"
  ([^MongoDatabase db coll keys]
   (create-index db coll keys {}))
  ([^MongoDatabase db coll keys opts]
   (.createIndex (collection db coll opts) (document keys) (->IndexOptions opts))))

(defn create-indexes
  "Creates many indexes.

  Arguments:

  - `db` is a MongoDatabase
  - `coll` is a collection name
  - `indexes` is a collection of maps with the following keys:
    - `:keys` (mandatory) a document representing index keys, e.g. {:a 1}
    - `:name`
    - `:sparse?`
    - `:unique?`"
  ([^MongoDatabase db coll indexes]
   (create-indexes db coll indexes {}))
  ([^MongoDatabase db coll indexes opts]
   (->> indexes
        (map (fn [x] (IndexModel. (document (:keys x)) (->IndexOptions x))))
        (.createIndexes (collection db coll opts)))))

(defn list-indexes
  "Lists indexes."
  ([^MongoDatabase db coll]
   (list-indexes db coll {}))
  ([^MongoDatabase db coll opts]
   (->> (.listIndexes (collection db coll opts))
        (map #(from-document % true)))))

;;; Utility functions

(defn- with-transaction
  "Executes `body` in a transaction.

  `body` should be a fn with one or more mongo operations in it.
  Ensure `session` is passed as an option to each operation.

  e.g.
  (def s (.startSession conn))
  (with-transaction s
    (fn []
      (insert-one my-db \"coll\" {:name \"hello\"} {:session s})
      (insert-one my-db \"coll\" {:name \"world\"} {:session s})))"
  [session body]
  (.withTransaction session (reify TransactionBody
                              (execute [_] body))))