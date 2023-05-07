package nsf.controller;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.hyperledger.acy_py.generated.model.InvitationRecord;
import org.hyperledger.aries.AriesClient;
import org.hyperledger.aries.api.out_of_band.CreateInvitationFilter;
import org.hyperledger.aries.api.out_of_band.InvitationCreateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Optional;

public class ControllerVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(ControllerVerticle.class);

    // TODO DI
    private final AriesClient ariesClient;

    public ControllerVerticle(AriesClient ariesClient) {
        this.ariesClient = ariesClient;
    }

    @Override
    public void start(Promise<Void> promise) {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        router.get("/create-invitation").handler(this::createInvitation);
        router.post("/webhook/topic/basicmessages").handler(this::BasicMessageHandler);

//    int port = config().getInteger("http.port", 8080); // TODO CONFIG
        int port = 8081;
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

    /**
     * Handles post request for establishing a connection to a service provider given an invitation message JSON from
     * that service provider in the post body. This tells the ACA-Py agent that we have "received" the invitation
     * message, and progresses the state of the connection.
     */
    private void createInvitation(RoutingContext ctx){
        InvitationCreateRequest invitationCreateRequest = InvitationCreateRequest.builder()
                .accept(Arrays.asList("didcomm/aip1", "didcomm/aip2;env=rfc19"))
//                            .alias("Barry")
                .handshakeProtocols(Arrays.asList("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/didexchange/1.0"))
                .metadata(new JsonObject())
//                            .myLabel("Invitation to Barry")
                .protocolVersion("1.1")
                .usePublicDid(false)
                .build();
        try {
            Optional<InvitationRecord> optionalInvitationRecord = ariesClient.outOfBandCreateInvitation(
                    invitationCreateRequest,
                    CreateInvitationFilter.builder()
                            .autoAccept(Boolean.TRUE)
                            .build()
            );
            InvitationRecord invitationRecord = optionalInvitationRecord.orElseThrow(() -> new IOException("Did not initiate " +
                    "ACA-Py connection."));
            JsonObject jsonObject = new JsonObject().put("invitationId", invitationRecord.getInvitationId()).put("url", invitationRecord.getInvitationUrl());
            ctx.response().send(jsonObject.encode());
//            ctx.response().setStatusCode(200).end();

        } catch (IOException e) {
            logger.error("Failed to generate invitation.", e);
            ctx.response().setStatusCode(500).end();
            throw new RuntimeException(e);
        }
    }


    /**
     * Handles receival of basic message and sends the message to the required destination
     */
    private void BasicMessageHandler(RoutingContext ctx){
        JsonObject message = ctx.body().asJsonObject();

        String user_connection_id = message.getString("connection_id");
        JsonObject pushed_data = new JsonObject(message.getString("content"));
        JsonObject json_body_to_send = new JsonObject()
            .put("connection_id", user_connection_id)
            .put("data", pushed_data);

        // TODO: handle message: https://vertx.io/docs/vertx-core/java/#_writing_request_headers
        // Get an async object to control the completion of the test
        HttpClient client = vertx.createHttpClient();
        int port = Integer.parseInt(System.getenv().getOrDefault("BACKEND_API_PORT", "8000"));
        String host = System.getenv().getOrDefault("BACKEND_API_HOST", "localhost");
        client.request(HttpMethod.POST, port,
            host,
            "/api/stress_score/", response -> {
            HttpClientRequest request = response.result();
            request.response().onSuccess(final_response -> {
                System.out.println("Received response with status code " + final_response.statusCode());
            });
            request.putHeader("Content-Type", "application/json");
            request.end(json_body_to_send.encode());
        });


    }
}
