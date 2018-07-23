package com.advancedtelematic.director.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive0, Directive1}
import com.advancedtelematic.director.client.CoreClient
import com.advancedtelematic.director.data.Codecs._
import com.advancedtelematic.director.data.DeviceRequest.DeviceRegistration
import com.advancedtelematic.director.db.{DeviceRepositorySupport, FileCacheRepositorySupport, RepoNameRepositorySupport}
import com.advancedtelematic.director.manifest.Verifier.Verifier
import com.advancedtelematic.director.manifest.{AfterDeviceManifestUpdate, DeviceManifestUpdate}
import com.advancedtelematic.director.roles.Roles
import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.libats.http.UUIDKeyPath._
import com.advancedtelematic.libats.messaging.MessageBusPublisher
import com.advancedtelematic.libats.messaging_datatype.DataType.DeviceId
import com.advancedtelematic.libats.messaging_datatype.Messages.DeviceSeen
import com.advancedtelematic.libtuf.data.TufCodecs._
import com.advancedtelematic.libtuf.data.TufDataType.{RoleType, SignedPayload, TargetName, TufKey}
import com.advancedtelematic.libtuf_server.data.Marshalling.JsonRoleTypeMetaPath
import com.advancedtelematic.libtuf_server.keyserver.KeyserverClient
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.Json
import DirectorRoutes._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import slick.jdbc.MySQLProfile.api._

class DeviceResource(extractNamespace: Directive1[Namespace],
                     verifier: TufKey => Verifier,
                     coreClient: CoreClient,
                     val keyserverClient: KeyserverClient,
                     roles: Roles)
                    (implicit val db: Database, val ec: ExecutionContext, messageBusPublisher: MessageBusPublisher)
    extends DeviceRepositorySupport
    with FileCacheRepositorySupport
    with RepoNameRepositorySupport
    with RootFetcher {
  import akka.http.scaladsl.server.Directives._
  import akka.http.scaladsl.server.Route

  private val afterUpdate = new AfterDeviceManifestUpdate(coreClient)
  private val deviceManifestUpdate = new DeviceManifestUpdate(afterUpdate, verifier)

  def logDevice(namespace: Namespace, device: DeviceId): Directive0 = {
    val f = messageBusPublisher.publishSafe(DeviceSeen(namespace, device))
    onComplete(f).flatMap(_ => pass)
  }

  def registerDevice(ns: Namespace, device: DeviceId, regDev: DeviceRegistration): Route = {
    val primEcu = regDev.primary_ecu_serial

    regDev.ecus.find(_.ecu_serial == primEcu) match {
      case None => complete(Errors.PrimaryIsNotListedForDevice)
      case Some(_) => complete(deviceRepository.create(ns, device, primEcu, regDev.ecus))
    }
  }

  def findTargetMetadata(ns: Namespace, device: DeviceId, target: TargetName): Future[Json] = ???

  val route = extractNamespace { ns =>
    pathPrefix("device" / DeviceId.Path) { device =>
      path("targets" / TargetNamePath / "metadata") { targetName =>
        get {
          complete(findTargetMetadata(ns, device, targetName))
        }
      } ~
      post {
        (path("ecus") & entity(as[DeviceRegistration])) { regDev =>
          registerDevice(ns, device, regDev)
        }
      } ~
      put {
        path("manifest") {
          entity(as[SignedPayload[Json]]) { jsonDevMan =>
            onComplete(deviceManifestUpdate.setDeviceManifest(ns, device, jsonDevMan)) {
              case Success(v) => complete(v)
              case Failure(ex) => complete((StatusCodes.BadRequest, s"An error occurred: ${ex.getMessage}"))
            }
          }
        }
      } ~
      get {
        path(IntNumber ~ ".root.json") { version =>
          fetchRoot(ns, version)
        } ~
        path(JsonRoleTypeMetaPath) {
          case RoleType.ROOT =>  logDevice(ns, device) { fetchRoot(ns) }
          case RoleType.TARGETS =>
            val f = roles.fetchTargets(ns, device)
            complete(f)
          case RoleType.SNAPSHOT =>
            val f = roles.fetchSnapshot(ns, device)
            complete(f)
          case RoleType.TIMESTAMP =>
            val f = roles.fetchTimestamp(ns, device)
            complete(f)
        }
      }
    }
  }
}
