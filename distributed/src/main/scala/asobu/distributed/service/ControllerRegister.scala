package asobu.distributed.service

import akka.ConfigurationException
import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.util.Timeout
import asobu.distributed.{SystemValidator, EndpointDefinition, DefaultEndpointsRegistry, EndpointsRegistry}
import asobu.distributed.gateway.Endpoint.Prefix
import asobu.distributed.service._
import play.api.libs.json.JsObject
import play.routes.compiler.{HandlerCall, Route}

import scala.concurrent.Future

trait ControllerRegister {

  type ApiDocGenerator = (Prefix, Seq[Route]) ⇒ Option[JsObject]
  val voidApiDocGenerator: ApiDocGenerator = (_, _) ⇒ None

  def init(prefix: Prefix)(controllers: Controller*)(
    implicit
    system: ActorSystem,
    ao: Timeout,
    buildNumber: Option[BuildNumber] = None,
    apiDocGenerator: ApiDocGenerator = voidApiDocGenerator
  ): Future[List[EndpointDefinition]] = init(prefix → controllers.toList)

  /**
   * Init controllers (add their actions to [[ EndpointRegistry ]]
   *
   * @param controllers
   * @param system
   * @param ao
   * @param buildNumber
   * @param apiDocGenerator
   */
  def init(controllers: (Prefix, List[Controller])*)(
    implicit
    system: ActorSystem,
    ao: Timeout,
    buildNumber: Option[BuildNumber],
    apiDocGenerator: ApiDocGenerator
  ): Future[List[EndpointDefinition]] = {

    import system.dispatcher
    val registry: EndpointsRegistry = DefaultEndpointsRegistry()
    val rec: EndpointsRegistryClient = EndpointsRegistryClientImp(registry, buildNumber)
    val version = buildNumber.map(_.buildInfoBuildNumber)

    def registerController(prefix: Prefix, controller: Controller): Seq[Future[EndpointDefinition]] = {

      def findRoute(action: Action): Route = controller.routes.find { r ⇒
        val HandlerCall(packageName, controllerName, _, method, _) = r.call
        action.name == packageName + "." + controllerName + "." + method
      }.getOrElse {
        throw new Exception(s"Cannot find route for action ${action.name}") //todo: this should really be a compilation error, the next right thing to do is to let it blow up the application on start.
      }

      def addAction(action: Action, prefix: Prefix = Prefix.root): Future[EndpointDefinition] = {
        val epd: EndpointDefinition =
          action.endpointDefinition(findRoute(action), prefix, version)

        rec.add(epd).map(_ ⇒ epd)
      }

      controller.actions.map(addAction(_, prefix))

    }

    SystemValidator.validate match {
      case Left(error) ⇒ Future.failed(new ConfigurationException(error))
      case _ ⇒
        Future.sequence(controllers.flatMap {
          case (prefix, controllers) ⇒
            ApiDocumentationReporter(registry)(routes ⇒ apiDocGenerator(prefix, routes)).report(controllers)
            controllers.flatMap(registerController(prefix, _))
        }.toList)
    }
  }

}
