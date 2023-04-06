package nsf.controller;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.hyperledger.acy_py.generated.model.InvitationRecord;
import org.hyperledger.aries.AriesClient;
import org.hyperledger.aries.api.connection.ConnectionRecord;
import org.hyperledger.aries.api.out_of_band.CreateInvitationFilter;
import org.hyperledger.aries.api.out_of_band.InvitationCreateRequest;
import org.hyperledger.aries.api.out_of_band.InvitationMessage;
import org.hyperledger.aries.api.out_of_band.ReceiveInvitationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
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

//    int port = config().getInteger("http.port", 8080); // TODO CONFIG
        int port = 8080;
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
        // TODO: create out of bound invitation
        InvitationCreateRequest invitationCreateRequest = InvitationCreateRequest.builder().build();
        try {
            Optional<InvitationRecord> optionalInvitationRecord = ariesClient.outOfBandCreateInvitation(
                    invitationCreateRequest,
                    CreateInvitationFilter.builder().build()
            );
            InvitationRecord invitationRecord = optionalInvitationRecord.orElseThrow(() -> new IOException("Did not initiate " +
                    "ACA-Py connection."));
            JsonObject jsonObject = new JsonObject().put("invitationId", invitationRecord.getInvitationId()).put("url", invitationRecord.getInvitationUrl());
            ctx.response().send(jsonObject.encode());
            ctx.response().setStatusCode(200).end();

        } catch (IOException e) {
            logger.error("Failed to generate invitation.", e);
            ctx.response().setStatusCode(500).end();
            throw new RuntimeException(e);
        }
    }
}
