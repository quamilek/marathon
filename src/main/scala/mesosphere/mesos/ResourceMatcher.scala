package mesosphere.mesos

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.PortsMatcher
import mesosphere.mesos.protos.{ RangesResource, Resource }
import org.apache.log4j.Logger
import org.apache.mesos.Protos.Offer

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object ResourceMatcher {
  type Role = String

  private[this] val log = Logger.getLogger(getClass)

  case class ResourceMatch(cpuRole: Role, memRole: Role, diskRole: Role, ports: Seq[RangesResource])

  def matchResources(offer: Offer, app: AppDefinition, runningTasks: => Set[MarathonTask],
                     acceptedResourceRoles: Set[String] = Set("*")): Option[ResourceMatch] = {

    val groupedResources = offer.getResourcesList.asScala.groupBy(_.getName)

    def findScalarResourceRole(tpe: String, value: Double): Option[Role] =
      groupedResources.get(tpe).flatMap {
        _
          .filter(resource => acceptedResourceRoles(resource.getRole))
          .find { resource =>
            resource.getScalar.getValue >= value
          }.map(_.getRole)
      }

    def cpuRoleOpt: Option[Role] = findScalarResourceRole(Resource.CPUS, app.cpus)
    def memRoleOpt: Option[Role] = findScalarResourceRole(Resource.MEM, app.mem)
    def diskRoleOpt: Option[Role] = findScalarResourceRole(Resource.DISK, app.disk)

    def meetsAllConstraints: Boolean = {
      lazy val tasks = runningTasks
      val badConstraints = app.constraints.filterNot { constraint =>
        Constraints.meetsConstraint(tasks, offer, constraint)
      }

      if (badConstraints.nonEmpty) {
        log.warn(
          s"Offer did not satisfy constraints for app [${app.id}].\n" +
            s"Conflicting constraints are: [${badConstraints.mkString(", ")}]"
        )
      }

      badConstraints.isEmpty
    }

    def portsOpt: Option[Seq[RangesResource]] = new PortsMatcher(app, offer, acceptedResourceRoles).portRanges match {
      case None =>
        log.warn("App ports are not available in the offer.")
        None

      case x @ Some(portRanges) =>
        log.debug("Met all constraints.")
        x
    }

    for {
      cpuRole <- cpuRoleOpt
      memRole <- memRoleOpt
      diskRole <- diskRoleOpt
      portRanges <- portsOpt
      if meetsAllConstraints
    } yield ResourceMatch(cpuRole, memRole, diskRole, portRanges)
  }
}