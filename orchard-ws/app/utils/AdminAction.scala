package utils

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}

import play.api.Logging
import play.api.libs.json.JsNull
import play.api.mvc._

class AdminAction @Inject() (parser: BodyParsers.Default, auth: Authorization)(implicit
  ec: ExecutionContext
) extends ActionBuilderImpl(parser) with Logging {

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]) =
    auth.getRoles(request) match {
      case Nil =>
        logger.debug("auth header does not exist")
        Future.successful(Results.Unauthorized(JsNull))
      case xs if xs.contains(Authorization.Admin) =>
        block(request)
      case _ =>
        logger.debug("insufficient privilege")
        Future.successful(Results.Forbidden(JsNull))
    }

}
