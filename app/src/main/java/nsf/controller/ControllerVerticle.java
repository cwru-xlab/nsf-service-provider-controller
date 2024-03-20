package nsf.controller;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.hyperledger.acy_py.generated.model.InvitationRecord;
import org.hyperledger.acy_py.generated.model.SendMessage;
import org.hyperledger.aries.AriesClient;
import org.hyperledger.aries.api.connection.ConnectionFilter;
import org.hyperledger.aries.api.out_of_band.CreateInvitationFilter;
import org.hyperledger.aries.api.out_of_band.InvitationCreateRequest;
import org.hyperledger.aries.api.present_proof.PresentProofRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

public class ControllerVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(ControllerVerticle.class);

    // TODO DI
    private final MongoClient mongoClient;
    private final String INVITATIONS_COLLECTION = "invitations";
    private final String PARTICIPANTS_COLLECTION = "participants";
    private final String SHARED_DATA_ITEMS_COLLECTION = "shared_data_items";
    private final String DATA_MENU_SETTINGS_COLLECTION = "data_menu_settings";
    private final AriesClient ariesClient;

    Random random = new Random();

    public ControllerVerticle(MongoClient mongoClient, AriesClient ariesClient) {
        this.mongoClient = mongoClient;
        this.ariesClient = ariesClient;
    }

    @Override
    public void start(Promise<Void> promise) {
        Router router = Router.router(vertx);
//        router.route().handler(CorsHandler.create("*")
//            .allowedMethod(HttpMethod.GET)
//            .allowedMethod(HttpMethod.POST)
//            .allowedMethod(HttpMethod.OPTIONS)
//            .allowedMethod(HttpMethod.DELETE)
//            .allowedMethod(HttpMethod.PATCH)
//            .allowedMethod(HttpMethod.PUT)
//            .allowCredentials(true)
//            .allowedHeader("Access-Control-Allow-Headers")
//            .allowedHeader("Authorization")
//            .allowedHeader("Access-Control-Allow-Method")
//            .allowedHeader("Access-Control-Allow-Origin")
//            .allowedHeader("Access-Control-Allow-Credentials")
//            .allowedHeader("Content-Type"));
        router.route().handler(BodyHandler.create());

        router.route().handler(ctx -> {
            ctx.response()
                    .putHeader("Access-Control-Allow-Origin", "*")
                    .putHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE, PATCH, PUT")
                    .putHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
                    .putHeader("Access-Control-Allow-Credentials", "true");

            if (ctx.request().method() == HttpMethod.OPTIONS) {
                ctx.response().setStatusCode(200).end();
            } else {
                ctx.next();
            }
        });

        router.get("/participants").handler(this::listParticipants);

        router.get("/invitations").handler(this::listInvitations);
        router.post("/invitations").handler(this::createInvitation);
        router.delete("/invitations/:invitationId").handler(this::deleteInvitation);


        router.get("/data-menu-settings").handler(this::getDataMenuSettingsHandler);
        router.put("/data-menu-settings").handler(this::setDataMenuSettings);

        router.post("/pull-data").handler(this::pullDataHandler);

        router.get("/collected-data").handler(this::getCollectedData);


        router.post("/webhook/topic/basicmessages").handler(this::BasicMessageHandler);
        router.post("/webhook/topic/connections").handler(this::connectionsUpdateHandler);
        router.post("/webhook/topic/out_of_band").handler(this::outOfBandHandler);
        router.post("/webhook/topic/present_proof").handler(this::presentProofUpdate);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9081"));
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .onSuccess(server -> {
                    // TODO LOGGING
                    logger.info(String.format("server running! (Should be listening at port %s)", port));
                    promise.complete();
                })
                .onFailure(promise::fail);
    }

    private void getCollectedData(RoutingContext ctx){
        JsonObject allQuery = new JsonObject();
        mongoClient.find(SHARED_DATA_ITEMS_COLLECTION, allQuery, h -> {
            if (h.succeeded()){
                JsonArray response = new JsonArray(h.result());
                ctx.response().setStatusCode(200).end(response.encode());
            }
            else{
                ctx.response().setStatusCode(500).end();
            }
        });
    }


    private void setDataMenuSettings(RoutingContext ctx){
        var newDataMenuSettings = ctx.body().asJsonObject();

        JsonObject dataMenuDoc = new JsonObject()
            .put("_id", "data_menu_settings")
            .put("data", newDataMenuSettings);

        mongoClient.save(DATA_MENU_SETTINGS_COLLECTION, dataMenuDoc, h -> {
            if (h.succeeded()){
                ctx.response().setStatusCode(200).end();
            }
            else{
                ctx.response().setStatusCode(500).end();
            }
        });
    }

    private void getDataMenuSettingsHandler(RoutingContext ctx){
        getDataMenuSettings()
            .onSuccess(dataMenuSettings -> {
                ctx.response().end(dataMenuSettings.encode());
            })
            .onFailure(e -> {
                ctx.response().setStatusCode(500).end();
            });
    }

    private Future<JsonObject> getDataMenuSettings(){
        JsonObject query = new JsonObject()
            .put("_id", "data_menu_settings");
        return mongoClient.find(DATA_MENU_SETTINGS_COLLECTION, query)
            .compose(queryResults -> {
                Promise<JsonObject> promise = Promise.promise();

                if (queryResults.size() > 0){
                    promise.complete(queryResults.get(0).getJsonObject("data"));
                }
                else{
                    promise.complete(new JsonObject("""
                        {
                          "spotify": {
                            "name": "Spotify",
                            "items": {
                              "fav-artist": {
                                "name": "Most Played Artist"
                              },
                              "fav-song": {
                                "name": "Most Played Track"
                              },
                              "following-artists-count": {
                                "name": "Following Artists Count"
                              },
                              "spotify-subscription-level": {
                                "name": "Spotify Subscription Level"
                              },
                              "demo-item": {
                                "name": "Other Item (For Demo)"
                              }
                            }
                          },
                          "other-example": {
                            "name": "Other Example Source",
                            "items": {
                              "example": {
                                "name": "Example Data Item"
                              }
                            }
                          }
                        }
                        """));
                }

                return promise.future();
            });
    }

    /**
     * Gets the filtered user data menu view, based on what items are selected.
     */
    private Future<JsonObject> getUserDataMenu(){
        return getDataMenuSettings()
            .compose(dataMenuSettings -> {
                Promise<JsonObject> promise = Promise.promise();
                JsonObject userDataMenu = new JsonObject();

                try{
                    for (String dataSourceKey : dataMenuSettings.fieldNames()) {
                        JsonObject dataSource = dataMenuSettings.getJsonObject(dataSourceKey);
                        JsonObject dataSourceItems = dataSource.getJsonObject("items");

                        for (String dataItemKey : dataSourceItems.fieldNames()) {
                            JsonObject dataItem = dataSourceItems.getJsonObject(dataItemKey);
                            boolean selected = dataItem.getBoolean("selected", false);

                            // If selected, then add to the user data menu view:
                            if (selected){
                                // If the data source frame isn't there yet, then add it:
                                if (!userDataMenu.containsKey(dataSourceKey)){
                                    userDataMenu.put(dataSourceKey,
                                        new JsonObject()
                                            .put("name", dataSource.getString("name"))
                                            .put("items", new JsonObject())
                                    );
                                }

                                JsonObject userDataMenuSourceItems = userDataMenu
                                    .getJsonObject(dataSourceKey)
                                    .getJsonObject("items");
                                userDataMenuSourceItems.put(dataItemKey, dataItem);
                            }
                        }
                    }
                }
                catch (Exception e){
                    logger.error(e.toString());
                }

                promise.complete(userDataMenu);
                return promise.future();
            });
    }


    private void presentProofUpdate(RoutingContext ctx){
        try{
            JsonObject message = ctx.body().asJsonObject();

            logger.info("present_proof updated: " + message.encodePrettily());

            String userConnectionId = message.getString("connection_id");
            String state = message.getString("state");
            String initiator = message.getString("initiator");

            if (initiator.equals("self") && state.equals("verified")){
                var connectionOptional = ariesClient.connectionsGetById(userConnectionId);
                var connection = connectionOptional.orElseThrow();

                JsonObject document = new JsonObject()
                    .put("_id", userConnectionId)
                    .put("connId", userConnectionId)
                    .put("createdAt", Instant.now().getEpochSecond())
                    .put("invitationKey", connection.getInvitationKey());
                mongoClient.save(PARTICIPANTS_COLLECTION, document);

                sendBasicMessage(userConnectionId, "VERIFY_RESPONSE", true, null);

                logger.info("added participant: " + userConnectionId);
            }

            ctx.response().setStatusCode(200).end();
        }
        catch(Exception e){
            ctx.response().setStatusCode(500).end();
        }
    }

    private void connectionsUpdateHandler(RoutingContext ctx){
        try{
            JsonObject message = ctx.body().asJsonObject();

            // Docs: https://aca-py.org/latest/features/AdminAPI/#pairwise-connection-record-updated-connections
            String userConnectionId = message.getString("connection_id");
            String state = message.getString("state");

            logger.info("connection updated: " + userConnectionId + ", " + state + " - " + message.encodePrettily());

            // TODO respond with details like name, description, access requests, etc.
            if (state.equals("active")){
                logger.info("connection completed, requesting present_proof: " + userConnectionId);

                JsonObject serverBannerData = new JsonObject()
                    .put("name", "Demo Service Provider")
                    .put("desc", "Example service provider for M.S. project prototype implementation demo. Requires demo credential to connect.");

                ariesClient.presentProofSendRequest(PresentProofRequest.builder()
                        .connectionId(userConnectionId)
                        .autoVerify(true)
                        .proofRequest(PresentProofRequest.ProofRequest.builder()
                            .name(serverBannerData.encode())
                            .requestedAttributes(Map.of(
                                "DL_number_referent",
                                PresentProofRequest.ProofRequest.ProofRequestedAttributes.builder()
                                    .name("DL_number")
                                    .clearRestrictions() // TODO UTyGiqDxFVe5dyboi87kp2:3:CL:439783:issuer-kit-demo
                                    .build()))
                            .build())
                        .build());
            }

            ctx.response().setStatusCode(200).end();
        }
        catch(Exception e){
            ctx.response().setStatusCode(500).end();
        }
    }

    private void outOfBandHandler(RoutingContext ctx){
        try{
            JsonObject message = ctx.body().asJsonObject();

            String user_connection_id = message.getString("connection_id");
            String invitation_message_id = message.getString("invi_msg_id");

            logger.info("out of band webhook: " + user_connection_id + ", " + invitation_message_id);

            ctx.response().setStatusCode(200).end();
        }
        catch(Exception e){
            ctx.response().setStatusCode(500).end();
        }
    }

    private void listParticipants(RoutingContext ctx){
        JsonObject query = new JsonObject();
        mongoClient.find(PARTICIPANTS_COLLECTION, query)
                .onSuccess(participants -> {

//                    // Append the name of the invitation that the participant used to connect, for each participant:
//                    for (var participant : participants){
//                        String invitationName = "";
//
//                        mongoClient.find(PARTICIPANTS_COLLECTION, query).onSuccess(participants -> {
//
//                        });
//
//                        participant.put("invitationName", invitationName);
//                    }

                    ctx.response().send(new JsonArray(participants).encode());
                })
                .onFailure(e -> {
                    ctx.response().setStatusCode(500).end();
                });
    }

    private void listInvitations(RoutingContext ctx){
//        try{
////            Optional<List<ConnectionRecord>> invitationsOptional = ariesClient.connections(ConnectionFilter.builder().state(ConnectionState.INVITATION).build());
////            List<ConnectionRecord> invitations = invitationsOptional.orElse(List.of());
////
////            JsonArray invitationsJson = new JsonArray();
////            invitations.forEach(record -> {
////                invitationsJson.add(new JsonObject().put("invKey", record.getInvitationKey()));
////            });
//        }
//        catch(Exception e){
//            ctx.response().setStatusCode(500).end();
//        }

        JsonObject query = new JsonObject();
        mongoClient.find(INVITATIONS_COLLECTION, query)
            .onSuccess(invitations -> {
                ctx.response().send(new JsonArray(invitations).encode());
            })
            .onFailure(e -> {
                ctx.response().setStatusCode(500).end();
            });
    }

    private void deleteInvitation(RoutingContext ctx){
        String invitationConnectionId = ctx.pathParam("invitationId");

        JsonObject query = new JsonObject()
                .put("_id", invitationConnectionId);
        mongoClient.removeDocument(INVITATIONS_COLLECTION, query)
                .onSuccess(invitations -> {
                    try {
                        ariesClient.connectionsRemove(invitationConnectionId);
                        ctx.response().setStatusCode(200).end();
                    } catch (IOException e) {
                        ctx.response().setStatusCode(500).end();
                    }
                })
                .onFailure(e -> {
                    ctx.response().setStatusCode(500).end();
                });
    }

    private void createInvitation(RoutingContext ctx){
        try{
            String name = ctx.body().asJsonObject().getString("name");

            String temporaryKey = LocalDateTime.now().toString();
            var invitationRecord = createAriesInvitation(temporaryKey);
            String url = invitationRecord.getInvitationUrl();

            // Some relevant fields are only in the ConnectionRecord and not the InvitationRecord, so we get the ConnectionRecord:
            var invitationConnectionQuery = ariesClient.connections(ConnectionFilter.builder().alias(temporaryKey).build());
            if (invitationConnectionQuery.isEmpty() || invitationConnectionQuery.get().size() != 1){
                logger.error("failed to find the invitation connection record.");
                ctx.response().setStatusCode(500).end();
                return;
            }

            var invitationConnection = invitationConnectionQuery.get().get(0);

            JsonObject document = new JsonObject()
                    .put("_id", invitationConnection.getInvitationKey())
                    .put("invitationKey", invitationConnection.getInvitationKey())
                    .put("invitationConnId", invitationConnection.getConnectionId())
                    .put("invitationMsgId", invitationRecord.getInviMsgId())
                    .put("name", name)
                    .put("createdAt", Instant.now().getEpochSecond())
                    .put("url", url);

            mongoClient.save(INVITATIONS_COLLECTION, document, h -> {
                if (h.succeeded()){
                    ctx.response().send(document.encode());
                }
                else{
                    ctx.response().setStatusCode(500).end();
                }
            });
        }
        catch(Exception e){
            ctx.response().setStatusCode(500).end();
        }
    }

    /**
     * Handles post request for establishing a connection to a service provider given an invitation message JSON from
     * that service provider in the post body. This tells the ACA-Py agent that we have "received" the invitation
     * message, and progresses the state of the connection.
     *
     * The tracking ID is for identifying/distinguishing between different invitations.
     */
    private InvitationRecord createAriesInvitation(String alias){
        InvitationCreateRequest invitationCreateRequest = InvitationCreateRequest.builder()
                .accept(Arrays.asList("didcomm/aip1", "didcomm/aip2;env=rfc19"))
//                            .alias("Barry")
                .handshakeProtocols(Arrays.asList("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/didexchange/1.0"))
                .metadata(new JsonObject())
                .protocolVersion("1.1")
                .usePublicDid(false)
                .alias(alias) // Alias seems to not be in the invite, but stored locally. Docs say it's "a local alias for the connection record".
                .build();
        try {
            Optional<InvitationRecord> optionalInvitationRecord = ariesClient.outOfBandCreateInvitation(
                    invitationCreateRequest,
                    CreateInvitationFilter.builder()
                            .autoAccept(true)
                            .multiUse(true) // multiple users can use this invitation.
                            .build()
            );
            InvitationRecord invitationRecord = optionalInvitationRecord.orElseThrow(() -> new IOException("Did not initiate " +
                    "ACA-Py connection."));
            return invitationRecord;

        } catch (IOException e) {
            logger.error("Failed to generate invitation.", e);
            throw new RuntimeException(e);
        }
    }

    private void pullDataHandler(RoutingContext ctx) {

    }

//    private void sendMessageToConnection(JsonObject jsonData, String connId){
//        // Build the ACA-Py Basic Message to send:
//        SendMessage basicMessageResponse = SendMessage.builder()
//                .content(jsonData.toString())
//                .build();
//
//        // Send the Basic Message via ACA-Py client:
//        try {
//            ariesClient.connectionsSendMessage(connId, basicMessageResponse);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//    }

    private Future<JsonObject> getInfoData(){
        return getUserDataMenu()
            .compose(userDataMenu -> {
                Promise<JsonObject> promise = Promise.promise();
                promise.complete(new JsonObject()
                    .put("dataMenu", userDataMenu));
                return promise.future();
            });
    }

    private String generateMsgId(String connId){
        return connId + "-" + String.valueOf(random.nextInt());
    }
    private void sendBasicMessage(String connId, String messageTypeId, Object dataPayload, String messageId){
        if (messageId == null){
            messageId = generateMsgId(connId);
        }

        JsonObject packagedJsonObj = new JsonObject()
            .put("uniqueMessageId", messageId + "-" + String.valueOf(random.nextInt()))
            .put("messageId", messageId)
            .put("messageTypeId", messageTypeId)
            .put("payload", dataPayload);

        SendMessage basicMessageResponse = SendMessage.builder()
            .content(packagedJsonObj.encode())
            .build();

        try {
            ariesClient.connectionsSendMessage(connId, basicMessageResponse);
        } catch (IOException e) {
            logger.error("Failed to send info response to " + connId + ": " + e.toString());
        }
    }

    private void saveSharedData(String connId, JsonArray dataSharePayload, String messageId){
        logger.info("Received shared data: " + dataSharePayload.encodePrettily());

        JsonObject query = new JsonObject()
            .put("_id", connId);
        mongoClient.find(PARTICIPANTS_COLLECTION, query)
            .onSuccess(participantResults -> {
                if (participantResults.size() > 0){
                    for (Object dataItemShareObject : dataSharePayload){
                        JsonObject dataItemShare = (JsonObject)dataItemShareObject;
                        JsonObject sharedDataItemDoc = new JsonObject()
                            .put("participantId", connId)
                            .put("epoch_seconds", Instant.now().getEpochSecond())
                            .put("dataSourceId", dataItemShare.getString("dataSourceId"))
                            .put("dataItemId", dataItemShare.getString("dataItemId"))
                            .put("data", dataItemShare.getValue("data"));
                        mongoClient.save(SHARED_DATA_ITEMS_COLLECTION, sharedDataItemDoc);
                    }
                    logger.info("Accepted shared data.");
                    sendBasicMessage(connId, "SHARED_DATA_ACK", dataSharePayload.size(), messageId);
                }
                else{
                    logger.warn("User not verified - rejecting shared data.");
                    sendBasicMessage(connId, "SHARED_DATA_ACK", -1, messageId);
                }
            });
    }

    HashSet<String> uniqueMessagesMap = new HashSet<>();

    /**
     * Handles receival of DIDComm basic message and sends the message to the required destination.
     */
    private void BasicMessageHandler(RoutingContext webhookCtx){
        JsonObject message = webhookCtx.body().asJsonObject();

        String connId = message.getString("connection_id");
        JsonObject basicMessagePackage = new JsonObject(message.getString("content"));

        String uniqueMessageId = basicMessagePackage.getString("uniqueMessageId");
        if (uniqueMessagesMap.contains(uniqueMessageId)){
            logger.warn("Duplicate message: " + message.encodePrettily());
            return;
        }
        uniqueMessagesMap.add(uniqueMessageId);

//        String threadNonceId = basicMessagePackage.getString("threadNonceId");
        String messageId = basicMessagePackage.getString("messageId");
        String messageTypeId = basicMessagePackage.getString("messageTypeId");

//        logger.info("Received basic message: " + message.encodePrettily());
        logger.info("Received basic message: " + messageTypeId);

        switch (messageTypeId){
            case "ESTABLISH_DATA_CONN_REQUEST": // a user wants to establish a connection with us.
                break;
            case "INFO_REQUEST": // a user wants to get the current data menu info, etc.
                getInfoData()
                    .onSuccess(infoData -> {
                        sendBasicMessage(connId, "INFO_RESPONSE", infoData, messageId);
                    });
                break;
            case "SHARED_DATA": // a user shared data to us.
                JsonArray payloadData = basicMessagePackage.getJsonArray("payload");
                saveSharedData(connId, payloadData, messageId);
                break;
            case "ABANDONED_DATA_CONN": // a user left / closed a connection with us.
                break;
            default:
                logger.error("basic message did not match a message type: " + messageTypeId);
                break;
        }

        webhookCtx.response().setStatusCode(200).end();

//        String stress_score_date_timestamp = pushed_data.getJsonObject("stress-score-data").getString("timestamp");
//
//        // TODO REMOVE BELOW:
//        JsonObject json_body_to_send = new JsonObject()
//            .put("connection_id", user_connection_id)
//            .put("date_time", stress_score_date_timestamp)
//            .put("data", pushed_data);
//        System.out.println("Sending stress score to backend..." + json_body_to_send.toString());
//        // TODO: handle message: https://vertx.io/docs/vertx-core/java/#_writing_request_headers
//        // Get an async object to control the completion of the test
//        //HttpClient client = vertx.createHttpClient();
//        WebClient client = WebClient.create(vertx);
//        int port = Integer.parseInt(System.getenv().getOrDefault("BACKEND_API_PORT", "8000"));
//        String host = System.getenv().getOrDefault("BACKEND_API_HOST", "localhost");
//        client.post(port, host, "/api/stress_score/")
//            .expect(ResponsePredicate.JSON)
//            .sendJsonObject(json_body_to_send)
//            .onSuccess(res -> {
//                System.out.println("Received response with status code " + res.statusCode());
//                System.out.println("Received response: " + res.bodyAsString());
//            })
//            .onFailure(err -> {
//                System.out.println("ERROR SENDING TO BACKEND " + err.getMessage());
//            });



//         response -> {
//                    HttpClientRequest request = response.result();
//                    request.response().onSuccess(final_response -> {
//                        System.out.println("Received response with status code " + final_response.statusCode());
//                    });
//                    request.putHeader("Content-Type", "application/json");
//                    request.end(json_body_to_send.encode());
//                }
    }
}
