package com.pvub.vertxstarter;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava.config.ConfigRetriever;
import io.vertx.rxjava.core.AbstractVerticle;
import io.vertx.rxjava.core.http.HttpServerResponse;
import io.vertx.rxjava.ext.web.Router;
import io.vertx.rxjava.ext.web.RoutingContext;
import io.vertx.rxjava.ext.web.handler.CorsHandler;
import io.vertx.rxjava.ext.web.handler.StaticHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Scheduler;
import rx.schedulers.Schedulers;

/**
 * Verticle to handle API requests
 * @author Udai
 */
public class StarterVerticle extends AbstractVerticle {
    static final int WORKER_POOL_SIZE = 20;

    private final Logger    m_logger;
    private Router          m_router;
    private ResourceAPI     m_api;
    private Integer         m_max_delay_milliseconds;
    private JsonObject      m_config = null;
    private ExecutorService m_worker_executor = null;
    private Scheduler       m_scheduler;
    private AtomicLong      m_latency = new AtomicLong(0);
    
    public StarterVerticle() {
        super();
        m_logger = LoggerFactory.getLogger("APIVERTICLE");
    }
    @Override
    public void start() throws Exception {
        m_logger.info("Starting StarterVerticle");

        ConfigRetriever retriever = ConfigRetriever.create(vertx);
        retriever.getConfig(
            config -> {
                m_logger.info("config retrieved");
                if (config.failed()) {
                    m_logger.info("No config");
                } else {
                    m_logger.info("Got config");
                    m_config = config.result();
                    m_max_delay_milliseconds = m_config.getInteger("max-delay-milliseconds", 1000);
                    Integer worker_pool_size = m_config.getInteger("worker-pool-size", Runtime.getRuntime().availableProcessors() * 2);
                    m_logger.info("max_delay_milliseconds={} worker_pool_size={}", m_max_delay_milliseconds, worker_pool_size);
                    m_worker_executor = Executors.newFixedThreadPool(worker_pool_size);
                    m_scheduler = Schedulers.from(m_worker_executor);
                    m_api = new ResourceAPI(m_worker_executor, m_max_delay_milliseconds);
                    m_api.build();
                    // Create a router object.
                    m_router = Router.router(vertx);

                    // Handle CORS requests.
                    m_router.route().handler(CorsHandler.create("*")
                        .allowedMethod(HttpMethod.GET)
                        .allowedMethod(HttpMethod.OPTIONS)
                        .allowedHeader("Accept")
                        .allowedHeader("Authorization")
                        .allowedHeader("Content-Type"));

                    m_router.get("/health").handler(this::generateHealth);
                    m_router.get("/api/resources/:id").handler(this::getOne);
                    m_router.route("/static/*").handler(StaticHandler.create());
                    
                    int port = m_config.getInteger("port", 8080);
                    // Create the HTTP server and pass the 
                    // "accept" method to the request handler.
                    vertx
                        .createHttpServer()
                        .requestHandler(m_router::accept)
                        .listen(
                            // Retrieve the port from the 
                            // configuration, default to 8080.
                            port,
                            result -> {
                                if (result.succeeded()) {
                                    m_logger.info("Listening now on port {}", port);
                                } else {
                                    m_logger.error("Failed to listen", result.cause());
                                }
                            }
                        );
                }
            }
        );
    }
    
    @Override
    public void stop() throws Exception {
        m_logger.info("Stopping StarterVerticle");
    }
    
    private void getOne(RoutingContext rc) {
        HttpServerResponse response = rc.response();
        String id = rc.request().getParam("id");
        m_logger.info("Request for {}", id);
        int idAsInt = Integer.parseInt(id);
        long startTS = System.nanoTime();
        
        m_api.fetchResource(idAsInt)
                .subscribeOn(m_scheduler)
                .observeOn(m_scheduler)
                .subscribe(r -> {
                    if (response.closed() || response.ended()) {
                        return;
                    }
                    response
                            .setStatusCode(201)
                            .putHeader("content-type", 
                              "application/json; charset=utf-8")
                            .end(Json.encodePrettily(r));
                }, 
                e -> {
                    m_logger.info("Sending response for request {}", id);
                    m_latency.addAndGet((System.nanoTime() - startTS)/1000000);
                    if (response.closed() || response.ended()) {
                        return;
                    }
                    response
                            .setStatusCode(404)
                            .putHeader("content-type", 
                              "application/json; charset=utf-8")
                            .end();
                }, () -> {});
    }
    public void generateHealth(RoutingContext ctx) {
        ctx.response()
            .setChunked(true)
            .putHeader("Content-Type", "application/json;charset=UTF-8")
            .putHeader("Access-Control-Allow-Methods", "GET")
            .putHeader("Access-Control-Allow-Origin", "*")
            .putHeader("Access-Control-Allow-Headers", "Accept, Authorization, Content-Type")
            .write((new JsonObject().put("status", "OK")).encode())
            .end();
    }

}