package woe.twin;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.DispatcherSelector;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.ShardedDaemonProcess;
import akka.management.cluster.bootstrap.ClusterBootstrap;
import akka.management.javadsl.AkkaManagement;
import akka.projection.ProjectionBehavior;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

public class Main {
  static Behavior<Void> create() {
    return Behaviors.setup(
        context -> Behaviors.receive(Void.class)
            .onSignal(Terminated.class, signal -> Behaviors.stopped())
            .build()
    );
  }

  public static void main(String[] args) {
    ActorSystem<?> actorSystem = ActorSystem.create(Main.create(), "woe-twin");
    startClusterBootstrap(actorSystem);
    startHttpServer(actorSystem);
    startClusterSharding(actorSystem);
    startProjectionSharding(actorSystem);
    //IntStream.rangeClosed(3, 18).forEach(zoom -> startProjectionSharding(actorSystem, zoom));
  }

  private static void startClusterBootstrap(ActorSystem<?> actorSystem) {
    AkkaManagement.get(actorSystem.classicSystem()).start();
    ClusterBootstrap.get(actorSystem.classicSystem()).start();
  }

  static void startHttpServer(ActorSystem<?> actorSystem) {
    try {
      String host = InetAddress.getLocalHost().getHostName();
      int port = actorSystem.settings().config().getInt("woe.twin.http.server.port");
      HttpServer.start(host, port, actorSystem);
    } catch (UnknownHostException e) {
      actorSystem.log().error("Http server start failure.", e);
    }
  }

  static void startClusterSharding(ActorSystem<?> actorSystem) {
    ClusterSharding clusterSharding = ClusterSharding.get(actorSystem);
    clusterSharding.init(
        Entity.of(
            Device.entityTypeKey,
            entityContext ->
                Device.create(entityContext.getEntityId(), clusterSharding)
        ).withEntityProps(DispatcherSelector.fromConfig("woe.twin.device-entity-dispatcher"))
    );
  }

  static void startProjectionSharding(ActorSystem<?> actorSystem) {
    final DeviceProjectorAllZooms.DbSessionFactory dbSessionFactory = new DeviceProjectorAllZooms.DbSessionFactory(actorSystem);
    final List<String> tags = Device.tagsAll(actorSystem);

    ShardedDaemonProcess.get(actorSystem).init(
        ProjectionBehavior.Command.class,
        "region-summary",
        tags.size(),
        id -> ProjectionBehavior.create(DeviceProjectorAllZooms.start(actorSystem, dbSessionFactory, tags.get(id))),
        ShardedDaemonProcessSettings.create(actorSystem),
        Optional.of(ProjectionBehavior.stopMessage())
    );
  }

  static void startProjectionSharding(ActorSystem<?> actorSystem, int zoom) {
    final DeviceProjectorSingleZoom.DbSessionFactory dbSessionFactory = new DeviceProjectorSingleZoom.DbSessionFactory(actorSystem);
    final List<String> tags = Device.tagsAll(actorSystem);

    ShardedDaemonProcess.get(actorSystem).init(
        ProjectionBehavior.Command.class,
        String.format("region-summary-%d", zoom),
        tags.size(),
        id -> ProjectionBehavior.create(DeviceProjectorSingleZoom.start(actorSystem, dbSessionFactory, tags.get(id), zoom)),
        ShardedDaemonProcessSettings.create(actorSystem),
        Optional.of(ProjectionBehavior.stopMessage())
    );
  }
}