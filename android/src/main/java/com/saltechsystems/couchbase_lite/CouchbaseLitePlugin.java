package com.saltechsystems.couchbase_lite;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.couchbase.lite.Array;
import com.couchbase.lite.Blob;
import com.couchbase.lite.BuildConfig;
import com.couchbase.lite.ConcurrencyControl;
import com.couchbase.lite.CouchbaseLite;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.Document;
import com.couchbase.lite.DocumentFlag;
import com.couchbase.lite.DocumentReplication;
import com.couchbase.lite.DocumentReplicationListener;
import com.couchbase.lite.ListenerToken;
import com.couchbase.lite.Query;
import com.couchbase.lite.QueryChange;
import com.couchbase.lite.QueryChangeListener;
import com.couchbase.lite.ReplicatedDocument;
import com.couchbase.lite.Replicator;
import com.couchbase.lite.ReplicatorChange;
import com.couchbase.lite.ReplicatorChangeListener;
import com.couchbase.lite.ResultSet;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/** CouchbaseLitePlugin */
public class CouchbaseLitePlugin implements CBManagerDelegate {
  private final Registrar mRegistrar;
  private final QueryEventListener mQueryEventListener = new QueryEventListener();
  private final ReplicationEventListener mReplicationEventListener = new ReplicationEventListener();
  private final CBManager mCBManager;
  private DatabaseCallHander databaseCallHander = new DatabaseCallHander();
  private ReplicatorCallHander replicatorCallHander = new ReplicatorCallHander();
  private JSONCallHandler jsonCallHandler = new JSONCallHandler();

  /**
   * Plugin registration.
   */
  public static void registerWith(Registrar registrar) {
    CouchbaseLitePlugin instance = new CouchbaseLitePlugin(registrar);

    final MethodChannel databaseChannel = new MethodChannel(registrar.messenger(), "com.saltechsystems.couchbase_lite/database");
    databaseChannel.setMethodCallHandler(instance.databaseCallHander);

    final MethodChannel replicatorChannel = new MethodChannel(registrar.messenger(), "com.saltechsystems.couchbase_lite/replicator");
    replicatorChannel.setMethodCallHandler(instance.replicatorCallHander);

    final MethodChannel jsonChannel = new MethodChannel(registrar.messenger(), "com.saltechsystems.couchbase_lite/json", JSONMethodCodec.INSTANCE);
    jsonChannel.setMethodCallHandler(instance.jsonCallHandler);

    final EventChannel replicationEventChannel = new EventChannel(registrar.messenger(), "com.saltechsystems.couchbase_lite/replicationEventChannel");
    replicationEventChannel.setStreamHandler(instance.mReplicationEventListener);

    final EventChannel queryEventChannel = new EventChannel(registrar.messenger(), "com.saltechsystems.couchbase_lite/queryEventChannel", JSONMethodCodec.INSTANCE);
    queryEventChannel.setStreamHandler(instance.mQueryEventListener);
  }

  public CouchbaseLitePlugin(Registrar registrar) {
    super();

    mRegistrar = registrar;

    CouchbaseLite.init(this.getContext());

    if (BuildConfig.DEBUG) {
      mCBManager = new CBManager(this,true);
    } else {
      mCBManager = new CBManager(this,false);
    }
  }

  @Override
  public String lookupKeyForAsset(String asset) {
    return mRegistrar.lookupKeyForAsset(asset);
  }

  @Override
  public AssetManager getAssets() {
    return mRegistrar.context().getAssets();
  }

  @Override
  public Context getContext() {
    return mRegistrar.context();
  }

  private class DatabaseCallHander implements MethodCallHandler {
    @Override
    public void onMethodCall(MethodCall call, Result result) {
      if (!call.hasArgument("database")) {
        result.error("errArgs", "Error: Missing database", call.arguments.toString());
        return;
      }

      String dbname = call.argument("database");
      Database database = mCBManager.getDatabase(dbname);
      String _id;
      ConcurrencyControl _concurrencyControl = null;
      if (call.hasArgument("concurrencyControl")) {
        String arg = call.argument("concurrencyControl");
        switch (arg) {
          case "failOnConflict":
            _concurrencyControl = ConcurrencyControl.FAIL_ON_CONFLICT;
          default:
            _concurrencyControl = ConcurrencyControl.LAST_WRITE_WINS;
        }
      }

      switch (call.method) {
        case ("initDatabaseWithName"):
          try {
            database = mCBManager.initDatabaseWithName(dbname);
            result.success(database.getName());
          } catch (Exception e) {
            result.error("errInit", "error initializing database with name " + dbname, e.toString());
          }
          break;
        case ("closeDatabaseWithName"):
          if (database == null) {
            result.error("errDatabase", "Database with name " + dbname + "not found", null);
            return;
          }

          try {
            mCBManager.closeDatabaseWithName(dbname);
            result.success(null);
          } catch (Exception e) {
            result.error("errClose", "error closing database with name " + dbname, e.toString());
          }
          break;
        case ("compactDatabaseWithName"):
          if (database == null) {
            result.error("errDatabase", "Database with name " + dbname + "not found", null);
            return;
          }

          try {
            database.compact();
            result.success(null);
          } catch (Exception e) {
            result.error("errCompact", "error compacting database with name " + dbname, e.toString());
          }
          break;
        case ("deleteDatabaseWithName"):
          try {
            mCBManager.deleteDatabaseWithName(dbname);
            result.success(null);
          } catch (Exception e) {
            result.error("errDelete", "error deleting database with name " + dbname, e.toString());
          }
          break;
        case ("saveDocument"):
          if (database == null) {
            result.error("errDatabase", "Database with name " + dbname + "not found", null);
            return;
          }

          if (_concurrencyControl != null && call.hasArgument("map")) {
            Map<String, Object> _document = call.argument("map");
            try {
              Map<String,Object> saveResult = mCBManager.saveDocument(database, _document, _concurrencyControl);
              result.success(saveResult);
            } catch (CouchbaseLiteException e) {
              result.error("errSave", "error saving document", e.toString());
            }
          } else {
            result.error("errArg", "invalid arguments", null);
          }
          break;
        case ("saveDocumentWithId"):
          if (database == null) {
            result.error("errDatabase", "Database with name " + dbname + "not found", null);
            return;
          }

          if (call.hasArgument("id") &&  _concurrencyControl != null && call.hasArgument("map")) {
            _id = call.argument("id");

            Map<String, Object> _map = call.argument("map");
            try {
              Map<String,Object> saveResult;

              Long sequence = null;
              if (call.hasArgument("sequence")) {
                Object sObj = call.argument("sequence");

                if (sObj instanceof Integer) {
                  sequence = Long.valueOf((Integer) sObj);
                } else if (sObj instanceof Long) {
                  sequence = (Long) sObj;
                }
              }

              if (sequence != null) {
                saveResult = mCBManager.saveDocumentWithId(database, _id, sequence, _map, _concurrencyControl);
              } else {
                saveResult = mCBManager.saveDocumentWithId(database, _id, _map, _concurrencyControl);
              }
              result.success(saveResult);
            } catch (CouchbaseLiteException e) {
              result.error("errSave", "error saving document with id " + _id, e.toString());
            }
          } else {
            result.error("errArg", "invalid arguments", null);
          }
          break;
        case ("getDocumentWithId"):
          if (database == null) {
            result.error("errDatabase", "Database with name " + dbname + "not found", null);
            return;
          }

          if (!call.hasArgument("id")) {
            result.error("errArgs", "Database Error: Invalid Arguments", call.arguments.toString());
            return;
          }

          _id = call.argument("id");
          result.success(mCBManager.getDocumentWithId(database, _id));
          break;
        case ("getBlobContentFromDocumentWithId"):
          if (database == null) {
            result.error("errDatabase", "Database with name " + dbname + "not found", null);
            return;
          }

          if (!call.hasArgument("id") || !call.hasArgument("key") || !call.hasArgument("digest")) {
            result.error("errArgs", "Database Error: Invalid Arguments", call.arguments.toString());
            return;
          }

          _id = call.argument("id");
          String _key = call.argument("key");
          String _digest = call.argument("digest");
          Document document = database.getDocument(_id);
          byte[] content = null;

          // Don't load the content if it isn't found or the digest doesn't match anymore
          if (document != null) {
            Blob _blob = document.getBlob(_key);
            if (_blob != null && _blob.digest().equals(_digest)) {
              content = _blob.getContent();
            }
          }

          result.success(content);
          break;
        case ("deleteDocumentWithId"):
          if (database == null) {
            result.error("errDatabase", "Database with name " + dbname + "not found", null);
            return;
          }

          if (!call.hasArgument("id")) {
            result.error("errArgs", "Database Error: Invalid Arguments", call.arguments.toString());
            return;
          }

          _id = call.argument("id");
          try {
            mCBManager.deleteDocumentWithId(database, _id);
            result.success(null);
          } catch (CouchbaseLiteException e) {
            result.error("errDelete", "error deleting document", e.toString());
          }

          break;
        case ("getDocumentCount"):
          if (database == null) {
            result.error("errDatabase", "Database with name " + dbname + "not found", null);
            return;
          }

          result.success(database.getCount());
          break;
        case ("getIndexes"):
          if (database == null) {
            result.error("errDatabase", "Database with name " + dbname + "not found", null);
            return;
          }

          try {
            result.success(database.getIndexes());
          } catch (CouchbaseLiteException e) {
            result.error("errIndexes", "error getting indexes for" + dbname, null);
          }

          break;
        default:
          result.notImplemented();
      }
    }
  }

  private class ReplicatorCallHander implements MethodCallHandler {
    @Override
    public void onMethodCall(MethodCall call, Result result) {
      if (!call.hasArgument("replicatorId")) {
        result.error("errArgs", "Error: Missing replicator", call.arguments.toString());
        return;
      }

      String replicatorId = call.argument("replicatorId");
      Replicator replicator = mCBManager.getReplicator(replicatorId);

      if (replicator == null) {
        result.error("errReplicator", "Error: Replicator already disposed", null);
        return;
      }

      switch (call.method) {
        case ("start"):
          replicator.start();

          result.success(null);
          break;
        case ("stop"):
          replicator.stop();

          result.success(null);
          break;
        case ("resetCheckpoint"):
          replicator.resetCheckpoint();

          result.success(null);
          break;
        case ("dispose"):
          mCBManager.removeReplicator(replicatorId);

          result.success(null);
          break;
        default:
          result.notImplemented();
      }
    }
  }

  private class JSONCallHandler implements MethodCallHandler {
    @Override
    public void onMethodCall(MethodCall call, final Result result) {
      final JSONObject json = call.arguments();

      final String id;
      Query queryFromJson;
      switch (call.method) {
        case ("executeQuery"):
          try {
            id = json.getString("queryId");
          } catch (JSONException e) {
            result.error("errArg", "Query Error: Invalid Arguments", e);
            return;
          }

          queryFromJson = mCBManager.getQuery(id);
          if (queryFromJson == null) {
            queryFromJson = new QueryJson(json,mCBManager).toCouchbaseQuery();
          }

          if (queryFromJson == null) {
            result.error("errQuery", "Error generating query", null);
            return;
          }

          final Query query = queryFromJson;
          AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
              try {
                final List<Map<String,Object>> resultsList = QueryJson.resultsToJson(query.execute());
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                  @Override
                  public void run() {
                    result.success(resultsList);
                  }
                });
              } catch (final CouchbaseLiteException e) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                  @Override
                  public void run() {
                    result.error("errQuery", "Error executing query", e.toString());
                  }
                });
              }
            }
          });
          break;
        case ("storeQuery"):
          try {
            id = json.getString("queryId");
          } catch (JSONException e) {
            result.error("errArg", "Query Error: Invalid Arguments", e);
            return;
          }

          queryFromJson = mCBManager.getQuery(id);
          if (queryFromJson == null) {
            queryFromJson = new QueryJson(json,mCBManager).toCouchbaseQuery();

            if (queryFromJson != null) {
              ListenerToken mListenerToken = queryFromJson.addChangeListener(AsyncTask.THREAD_POOL_EXECUTOR, new QueryChangeListener() {
                @Override
                public void changed(QueryChange change) {
                  final HashMap<String,Object> json = new HashMap<String,Object>();
                  json.put("query",id);

                  if (change.getResults() != null) {
                    json.put("results",QueryJson.resultsToJson(change.getResults()));
                  }

                  if (change.getError() != null) {
                    json.put("error",change.getError().getLocalizedMessage());
                  }

                  new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                      final EventChannel.EventSink eventSink = mQueryEventListener.mEventSink;
                      if (eventSink != null) {
                        eventSink.success(json);
                      }
                    }
                  });

                }
              });

              mCBManager.addQuery(id, queryFromJson, mListenerToken);
            }
          }

          result.success(queryFromJson != null);

          break;
        case ("removeQuery"):
          try {
            id = json.getString("queryId");
          } catch (JSONException e) {
            result.error("errArg", "Query Error: Invalid Arguments", e);
            return;
          }

          mCBManager.removeQuery(id);
          result.success(true);
          break;
        case ("storeReplicator"):
          try {
            id = json.getString("replicatorId");
          } catch (JSONException e) {
            result.error("errArg", "Query Error: Invalid Arguments", e);
            return;
          }

          Replicator replicator = new ReplicatorJson(json,mCBManager).toCouchbaseReplicator();
          if (replicator != null) {
            ListenerToken mListenerToken = replicator.addChangeListener(new ReplicatorChangeListener() {
              @Override
              public void changed(ReplicatorChange change) {
                HashMap<String,Object> json = new HashMap<String,Object>();
                json.put("replicator",id);
                json.put("type","ReplicatorChange");

                final EventChannel.EventSink mEventSink = mReplicationEventListener.mEventSink;
                if (mEventSink == null) {
                  return;
                }

                CouchbaseLiteException error = change.getStatus().getError();
                if (error != null) {
                  json.put("error",error.getLocalizedMessage());
                }

                switch (change.getStatus().getActivityLevel()) {
                  case BUSY:
                    json.put("activity","BUSY");
                    break;
                  case IDLE:
                    json.put("activity","IDLE");
                    break;
                  case OFFLINE:
                    json.put("activity","OFFLINE");
                    break;
                  case STOPPED:
                    json.put("activity","STOPPED");
                    break;
                  case CONNECTING:
                    json.put("activity","CONNECTING");
                    break;
                }

                mEventSink.success(json);
              }
            });

            ListenerToken mDocumentReplicationListenerToken = replicator.addDocumentReplicationListener(new DocumentReplicationListener() {
              @Override
              public void replication(DocumentReplication replication) {
                HashMap<String,Object> json = new HashMap<>();
                json.put("replicator",id);
                json.put("type","DocumentReplication");

                final EventChannel.EventSink mEventSink = mReplicationEventListener.mEventSink;
                if (mEventSink == null) {
                  return;
                }

                json.put("isPush",replication.isPush());

                ArrayList<HashMap<String,Object>> documents = new ArrayList<>();
                for (ReplicatedDocument document : replication.getDocuments()) {
                  HashMap<String,Object> documentReplication = new HashMap<>();
                  documentReplication.put("document",document.getID());
                  CouchbaseLiteException error = document.getError();
                  if (error != null) {
                    documentReplication.put("error",error.getLocalizedMessage());
                  }

                  int flags = 0;
                  for (DocumentFlag flag : document.flags()) {
                    flags += flag.rawValue();
                  }

                  documentReplication.put("flags",flags);
                  documents.add(documentReplication);
                }

                json.put("documents", documents);

                mEventSink.success(json);
              }
            });

            ListenerToken[] tokens = {mListenerToken, mDocumentReplicationListenerToken};
            mCBManager.addReplicator(id, replicator, tokens);
          } else {
            result.error("errReplicator", "Replicator Error: Failed to initialize replicator", null);
          }

          result.success(null);
          break;
        default:
          result.notImplemented();
      }
    }
  }
}
