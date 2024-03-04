package nsf;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;

public class MongoDbHelper {

    public static MongoClient getMongoClient(Vertx vertx) {
        JsonObject config = Vertx.currentContext().config();
        // TODO: add config file
        String uri = System.getenv().getOrDefault("MONGO_DB_URI", "mongodb://host.docker.internal:27018");
        String db = "service-provider-db";

        JsonObject mongoConfig = new JsonObject()
                .put("connection_string", uri)
                .put("db_name", db);

        return MongoClient.createShared(vertx, mongoConfig);
    }
}
