package com.redhat.cajun.navy.mission.http;


import com.redhat.cajun.navy.mission.MessageAction;
import com.redhat.cajun.navy.mission.cache.CacheAccessVerticle;
import com.redhat.cajun.navy.mission.data.Location;
import com.redhat.cajun.navy.mission.data.Mission;
import com.redhat.cajun.navy.mission.data.MissionCommand;
import com.redhat.cajun.navy.mission.data.MissionRoute;
import com.redhat.cajun.navy.mission.map.RoutePlanner;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.net.HttpURLConnection;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;


public class MissionRestVerticle extends CacheAccessVerticle {

    final String MISSIONS_EP = "/api/missions";

    private final Logger logger = Logger.getLogger(MissionRestVerticle.class.getName());

    public static final String CACHE_QUEUE = "cache.queue";

    public static final String PUB_QUEUE = "pub.queue";

    private static final String MAPBOX_ACCESS_TOKEN_KEY = "map.token";

    private String MAPBOX_ACCESS_TOKEN = null;

    @Override
    protected void init(Future<Void> startFuture) {
        String host = config().getString("http.host");
        int port = config().getInteger("http.port");
        MAPBOX_ACCESS_TOKEN = config().getString("map.token");


        Router router = Router.router(vertx);

        router.get("/").handler(rc -> {
            rc.response().putHeader("content-type", "text/html")
                    .end(" Missions API Service");
        });

        vertx.eventBus().consumer(config().getString(CACHE_QUEUE, "cache.queue"), this::onMessage);

        router.route().handler(BodyHandler.create());
        router.get(MISSIONS_EP).handler(this::getAll);
        // router.put(MISSIONS_EP).handler(this::addMission);
        router.get(MISSIONS_EP + "/:id").handler(this::missionById);
        vertx.createHttpServer()
                .requestHandler(router::accept)
                .listen(port, ar -> {
                    if (ar.succeeded()) {
                        startFuture.complete();
                    } else {
                        startFuture.fail(ar.cause());
                    }
                });
    }

    private void addMission(RoutingContext routingContext) {
        try {
            boolean prepareMessage = false;
            Mission m = Json.decodeValue(routingContext.getBodyAsString(), Mission.class);
            if(m.getId() == null){
                m.setId(UUID.randomUUID().toString());
                MissionRoute mRoute = new RoutePlanner(MAPBOX_ACCESS_TOKEN).getMapboxDirectionsRequest(
                        new Location(m.getResponderStartLat(), m.getResponderStartLong()),
                        new Location(m.getDestinationLat(), m.getDestinationLong()),
                        new Location(m.getIncidentLat(), m.getIncidentLong()));

                m.setRoute(mRoute);
            }
            else{
                prepareMessage = true;
            }
            logger.log(Level.INFO,"putting.. " + m.getId() + "\n " + m);
            defaultCache.putAsync(m.getId(), m.toString())
                    .whenComplete((s, t) -> {
                        if (t == null) {
                            routingContext.response()
                                    .setStatusCode(201)
                                    .putHeader("content-type", "application/json; charset=utf-8")
                                    .end(Json.encodePrettily(m));
                        } else {
                            routingContext.fail(500);
                        }
                    });

            if(prepareMessage){
                DeliveryOptions options = new DeliveryOptions().addHeader("action", MessageAction.PUBLISH_UPDATE.toString());
                vertx.eventBus().send(PUB_QUEUE, m.toString(), options, reply -> {
                    if (reply.succeeded()) {
                        System.out.println("Message publish request accepted");
                    } else {
                        System.out.println("Message publish request not accepted");
                    }
                });
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public enum ErrorCodes {
        NO_ACTION_SPECIFIED,
        BAD_ACTION
    }

    public void onMessage(Message<JsonObject> message) {

        if (!message.headers().contains("action")) {
            message.fail(ErrorCodes.NO_ACTION_SPECIFIED.ordinal(), "No action header specified");
            return;
        }

        String action = message.headers().get("action");
        switch (action) {
            case "CREATE_ENTRY":
                Mission m = Json.decodeValue(String.valueOf(message.body()), MissionCommand.class).getBody();
                m.setStatus("CREATED");
                MissionRoute mRoute = new RoutePlanner(MAPBOX_ACCESS_TOKEN).getMapboxDirectionsRequest(
                        new Location(m.getResponderStartLat(), m.getResponderStartLong()),
                        new Location(m.getDestinationLat(), m.getDestinationLong()),
                        new Location(m.getIncidentLat(), m.getIncidentLong()));

                m.setRoute(mRoute);

                defaultCache.putAsync(m.getId(), m.toString())
                        .whenComplete((s, t) -> {
                            if (t == null) {
                                message.reply(m.toString());
                                System.out.println(m.toString());
                            } else {
                                System.out.println(m.toString());
                            }
                        });


                // Possible issue here, since DG might not be updated and this message is publised for Mission Created.
                MissionCommand mc = new MissionCommand();
                mc.createMissionCommandHeaders("MissionCreatedEvent", "MissionService", System.currentTimeMillis());
                mc.setMission(m);

                DeliveryOptions options = new DeliveryOptions().addHeader("action", MessageAction.PUBLISH_UPDATE.toString());
                vertx.eventBus().send(PUB_QUEUE, mc.toString(), options, reply -> {
                    if (reply.succeeded()) {
                        System.out.println("Message publish request accepted");
                    } else {
                        System.out.println("Message publish request not accepted");
                    }
                });


                break;

            default:
                message.fail(ErrorCodes.BAD_ACTION.ordinal(), "Bad action: " + action);

        }
    }

    private void getAll(RoutingContext routingContext) {
// THIS METHOD IS INCOMPLETE
        Set<String> m = defaultCache.keySet();

        routingContext.response()
                .setStatusCode(201)
                .putHeader("content-type", "application/json; charset=utf-8")
                .end(Json.encodePrettily(m.toArray()));
    }

    private void missionById(RoutingContext routingContext) {

        String id = routingContext.request().getParam("id");
        logger.info("missionById: id=" + id);


        defaultCache.getAsync(routingContext.request().getParam("id"))
                .thenAccept(value -> {
                    int responseCode = HttpURLConnection.HTTP_CREATED;
                    if (value == null) {
                        responseCode = HttpURLConnection.HTTP_NO_CONTENT;
                        String.format("Mission id %s not found", id);
                        routingContext.response()
                                .setStatusCode(responseCode)
                                .end("Response:"+HttpURLConnection.HTTP_NO_CONTENT);
                    } else {
                        Mission m = Json.decodeValue(value, Mission.class);
                        routingContext.response()
                                .setStatusCode(responseCode)
                                .putHeader("content-type", "application/json; charset=utf-8")
                                .end(Json.encodePrettily(m));
                    }
                });

    }

}
