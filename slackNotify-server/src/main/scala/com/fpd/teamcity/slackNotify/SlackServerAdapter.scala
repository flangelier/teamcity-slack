package com.fpd.teamcity.slackNotify

import com.fpd.teamcity.slackNotify.ConfigManager.BuildSettingFlag
import com.fpd.teamcity.slackNotify.ConfigManager.BuildSettingFlag.BuildSettingFlag
import jetbrains.buildServer.serverSide.{BuildServerAdapter, SBuildServer, SRunningBuild}
import scala.collection.JavaConverters._

class SlackServerAdapter(sBuildServer: SBuildServer,
                         configManager: ConfigManager,
                         gateway: SlackGateway
                        ) extends BuildServerAdapter {

  import SlackServerAdapter._

  sBuildServer.addListener(this)

  private def notify(build: SRunningBuild, flags: Set[BuildSettingFlag]): Unit = {
    def matchBranch(mask: String) = Option(build.getBranch).map(branch ⇒ mask.r.findFirstIn(branch.getName)).isDefined

    val settings = configManager.buildSettingList(build.getBuildTypeId).values.filter { x ⇒
      x.flags == flags && matchBranch(x.branchMask)
    }

    settings.foreach { setting ⇒
      gateway.sendMessage(setting.slackChannel, messageByFlags(build, setting.flags))
    }
  }

  private def messageByFlags(build: SRunningBuild, flags: Set[BuildSettingFlag]): String = {
    val status = if (flags.contains(BuildSettingFlag.success)) {
      "succeeded"
    } else {
      "failed"
    }

    val project = sBuildServer.getProjectManager.findProjectById(build.getProjectId)

    s"Build #${build.getBuildId} (${project.getName}) $status"
  }

  override def buildFinished(build: SRunningBuild): Unit =
    calculateFlags(build, sBuildServer).foreach(flags ⇒ notify(build, flags))
}

object SlackServerAdapter {
  private def calculateFlags(implicit build: SRunningBuild, sBuildServer: SBuildServer) = {
    import BuildSettingFlag._

    val flags = collection.mutable.Set.empty[BuildSettingFlag]

    if (build.getBuildStatus.isSuccessful) {
      flags += success
      if (statusChanged) {
        flags += failureToSuccess
      }
    } else if (build.getBuildStatus.isFailed) {
      flags += failure
      if (statusChanged) {
        flags += successToFailure
      }
    }

    flags.size match {
      case 0 ⇒ None
      case _ ⇒ Some(flags.toSet)
    }
  }

  private def statusChanged(implicit build: SRunningBuild, sBuildServer: SBuildServer) = {
    val previousStatus = sBuildServer.getHistory.getEntriesBefore(build, false).asScala.find(!_.isPersonal).map(_.getBuildStatus)
    val current = build.getBuildStatus

    previousStatus.forall { prev ⇒
      if (prev.isSuccessful) {
        current.isFailed
      } else if (prev.isFailed) {
        current.isSuccessful
      } else {
        true
      }
    }
  }
}