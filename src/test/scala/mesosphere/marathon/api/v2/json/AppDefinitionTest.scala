package mesosphere.marathon
package api.v2.json

import com.wix.accord._
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.api.JsonTestHelper
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.core.health.{ MarathonHttpHealthCheck, MesosCommandHealthCheck, MesosHttpHealthCheck, PortReference }
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.core.readiness.ReadinessCheckTestHelper
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.PortMapping
import mesosphere.marathon.state.DiscoveryInfo.Port
import mesosphere.marathon.state.EnvVarValue._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper }
import org.apache.mesos.{ Protos => mesos }
import org.scalatest.Matchers
import play.api.data.validation.ValidationError
import play.api.libs.json.{ JsError, Json }

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class AppDefinitionTest extends MarathonSpec with Matchers {
  val validAppDefinition = AppDefinition.validAppDefinition(Set("secrets"))(PluginManager.None)

  ignore("Validation") {
    def shouldViolate(app: AppDefinition, path: String, template: String)(implicit validAppDef: Validator[AppDefinition] = validAppDefinition): Unit = {
      validate(app) match {
        case Success => fail(s"expected failure '$template'")
        case f: Failure =>
          val violations = ValidationHelper.getAllRuleConstrains(f)

          assert(
            violations.exists { v =>
              v.path.contains(path) && v.message == template
            },
            s"Violations:\n${violations.mkString}"
          )
      }
    }

    def shouldNotViolate(app: AppDefinition, path: String, template: String)(implicit validAppDef: Validator[AppDefinition] = validAppDefinition): Unit = {
      validate(app) match {
        case Success =>
        case f: Failure =>
          val violations = ValidationHelper.getAllRuleConstrains(f)
          assert(
            !violations.exists { v =>
              v.path.contains(path) && v.message == template
            },
            s"Violations:\n${violations.mkString}"
          )
      }
    }

    var app = AppDefinition(id = "a b".toRootPath)
    val idError = "must fully match regular expression '^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])|(\\.|\\.\\.)$'"
    MarathonTestHelper.validateJsonSchema(app, false)
    shouldViolate(app, "/id", idError)

    app = app.copy(id = "a#$%^&*b".toRootPath)
    MarathonTestHelper.validateJsonSchema(app, false)
    shouldViolate(app, "/id", idError)

    app = app.copy(id = "-dash-disallowed-at-start".toRootPath)
    MarathonTestHelper.validateJsonSchema(app, false)
    shouldViolate(app, "/id", idError)

    app = app.copy(id = "dash-disallowed-at-end-".toRootPath)
    MarathonTestHelper.validateJsonSchema(app, false)
    shouldViolate(app, "/id", idError)

    app = app.copy(id = "uppercaseLettersNoGood".toRootPath)
    MarathonTestHelper.validateJsonSchema(app, false)
    shouldViolate(app, "/id", idError)

    app = AppDefinition(
      id = "test".toPath,
      instances = -3,
      portDefinitions = PortDefinitions(9000, 8080, 9000)
    )
    shouldViolate(
      app,
      "/portDefinitions",
      "Ports must be unique."
    )
    MarathonTestHelper.validateJsonSchema(app, false)

    app = AppDefinition(
      id = "test".toPath,
      portDefinitions = PortDefinitions(0, 0, 8080),
      cmd = Some("true")
    )
    shouldNotViolate(
      app,
      "/portDefinitions",
      "Ports must be unique."
    )
    MarathonTestHelper.validateJsonSchema(app, true)

    app = AppDefinition(
      id = "test".toPath,
      cmd = Some("true"),
      container = Some(Docker(
        image = "mesosphere/marathon",
        network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
        portMappings = Seq(
          PortMapping(8080, Some(0), 0, "tcp", Some("foo")),
          PortMapping(8081, Some(0), 0, "tcp", Some("foo"))
        )
      )),
      portDefinitions = Nil
    )
    shouldViolate(
      app,
      "/container/portMappings",
      "Port names must be unique."
    )

    app = AppDefinition(
      id = "test".toPath,
      cmd = Some("true"),
      portDefinitions = Seq(
        PortDefinition(port = 9000, name = Some("foo")),
        PortDefinition(port = 9001, name = Some("foo"))
      )
    )
    shouldViolate(
      app,
      "/portDefinitions",
      "Port names must be unique."
    )

    val correct = AppDefinition(id = "test".toPath)

    app = correct.copy(
      container = Some(Docker(
        image = "mesosphere/marathon",
        portMappings = Seq(
          PortMapping(8080, Some(0), 0, "tcp", Some("foo")),
          PortMapping(8081, Some(0), 0, "tcp", Some("bar"))
        )
      )),
      portDefinitions = Nil)
    shouldNotViolate(
      app,
      "/container/portMappings",
      "Port names must be unique."
    )

    app = correct.copy(
      container = Some(Docker(
        image = "mesosphere/marathon",
        network = Some(mesos.ContainerInfo.DockerInfo.Network.USER),
        portMappings = Seq(
          PortMapping(8080, None, 0, "tcp", Some("foo"))
        )
      )),
      portDefinitions = Nil)
    shouldNotViolate(
      app,
      "/container/portMappings(0)",
      "hostPort is required for BRIDGE mode."
    )

    app = correct.copy(
      container = Some(Docker(
        image = "mesosphere/marathon",
        network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
        portMappings = Seq(
          PortMapping(8080, None, 0, "tcp", Some("foo"))
        )
      )),
      portDefinitions = Nil)
    shouldViolate(
      app,
      "/container/portMappings(0)",
      "hostPort is required for BRIDGE mode."
    )

    app = correct.copy(
      container = Some(Docker(
        image = "mesosphere/marathon",
        network = Some(mesos.ContainerInfo.DockerInfo.Network.USER),
        portMappings = Seq(
          PortMapping(8080, Some(0), 0, "tcp", Some("foo")),
          PortMapping(8081, Some(0), 0, "tcp", Some("bar"))
        )
      )),
      portDefinitions = Nil)
    shouldNotViolate(
      app,
      "/container/portMappings",
      "Port names must be unique."
    )

    // unique port names for USER mode
    app = correct.copy(
      container = Some(Docker(
        image = "mesosphere/marathon",
        network = Some(mesos.ContainerInfo.DockerInfo.Network.USER),
        portMappings = Seq(
          PortMapping(8080, Some(0), 0, "tcp", Some("foo")),
          PortMapping(8081, Some(0), 0, "tcp", Some("foo"))
        )
      )),
      portDefinitions = Nil)
    shouldViolate(
      app,
      "/container/portMappings",
      "Port names must be unique."
    )

    app = correct.copy(
      portDefinitions = Seq(
        PortDefinition(port = 9000, name = Some("foo")),
        PortDefinition(port = 9001, name = Some("bar"))
      )
    )
    shouldNotViolate(
      app,
      "/portDefinitions",
      "Port names must be unique."
    )

    app = correct.copy(executor = "//cmd")
    shouldNotViolate(
      app,
      "/executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    MarathonTestHelper.validateJsonSchema(app)

    app = correct.copy(executor = "some/relative/path.mte")
    shouldNotViolate(
      app,
      "/executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    MarathonTestHelper.validateJsonSchema(app)

    app = correct.copy(executor = "/some/absolute/path")
    shouldNotViolate(
      app,
      "/executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    MarathonTestHelper.validateJsonSchema(app)

    app = correct.copy(executor = "")
    shouldNotViolate(
      app,
      "/executor",
      "{javax.validation.constraints.Pattern.message}"
    )
    MarathonTestHelper.validateJsonSchema(app)

    app = correct.copy(executor = "/test/")
    shouldViolate(
      app,
      "/executor",
      "must fully match regular expression '^(//cmd)|(/?[^/]+(/[^/]+)*)|$'"
    )
    MarathonTestHelper.validateJsonSchema(app, false)

    app = correct.copy(executor = "/test//path")
    shouldViolate(
      app,
      "/executor",
      "must fully match regular expression '^(//cmd)|(/?[^/]+(/[^/]+)*)|$'"
    )
    MarathonTestHelper.validateJsonSchema(app, false)

    app = correct.copy(cmd = Some("command"), args = Seq("a", "b", "c"))
    shouldViolate(
      app,
      "/",
      "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
    )
    MarathonTestHelper.validateJsonSchema(app, false)

    app = correct.copy(cmd = None, args = Seq("a", "b", "c"))
    shouldNotViolate(
      app,
      "/",
      "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
    )
    MarathonTestHelper.validateJsonSchema(app)

    app = correct.copy(upgradeStrategy = UpgradeStrategy(1.2))
    shouldViolate(
      app,
      "/upgradeStrategy/minimumHealthCapacity",
      "got 1.2, expected between 0.0 and 1.0"
    )
    MarathonTestHelper.validateJsonSchema(app, false)

    app = correct.copy(upgradeStrategy = UpgradeStrategy(0.5, 1.2))
    shouldViolate(
      app,
      "/upgradeStrategy/maximumOverCapacity",
      "got 1.2, expected between 0.0 and 1.0"
    )
    MarathonTestHelper.validateJsonSchema(app, false)

    app = correct.copy(upgradeStrategy = UpgradeStrategy(-1.2))
    shouldViolate(
      app,
      "/upgradeStrategy/minimumHealthCapacity",
      "got -1.2, expected between 0.0 and 1.0"
    )
    MarathonTestHelper.validateJsonSchema(app, false)

    app = correct.copy(upgradeStrategy = UpgradeStrategy(0.5, -1.2))
    shouldViolate(
      app,
      "/upgradeStrategy/maximumOverCapacity",
      "got -1.2, expected between 0.0 and 1.0"
    )
    MarathonTestHelper.validateJsonSchema(app, false)

    app = correct.copy(
      container = Some(Docker(
        network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE),
        portMappings = Seq(
          PortMapping(8080, Some(0), 0, "tcp"),
          PortMapping(8081, Some(0), 0, "tcp")
        )
      )),
      portDefinitions = Nil,
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(1))))
    )
    shouldNotViolate(
      app,
      "/healthChecks(0)",
      "Health check port indices must address an element of the ports array or container port mappings."
    )
    MarathonTestHelper.validateJsonSchema(app, false) // missing image

    app = correct.copy(
      container = Some(Docker(
        network = Some(mesos.ContainerInfo.DockerInfo.Network.BRIDGE)
      )),
      portDefinitions = Nil,
      healthChecks = Set(MarathonHttpHealthCheck(port = Some(80)))
    )
    shouldNotViolate(
      app,
      "/healthChecks(0)",
      "Health check port indices must address an element of the ports array or container port mappings."
    )
    MarathonTestHelper.validateJsonSchema(app, false) // missing image

    app = correct.copy(
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(1))))
    )
    shouldViolate(
      app,
      "/healthChecks(0)",
      "Health check port indices must address an element of the ports array or container port mappings."
    )

    MarathonTestHelper.validateJsonSchema(app)

    app = correct.copy(
      fetch = Seq(FetchUri(uri = "http://example.com/valid"), FetchUri(uri = "d://\not-a-uri"))
    )

    shouldViolate(
      app,
      "/fetch(1)",
      "URI has invalid syntax."
    )

    MarathonTestHelper.validateJsonSchema(app)

    app = correct.copy(
      fetch = Seq(FetchUri(uri = "http://example.com/valid"), FetchUri(uri = "/root/file"))
    )

    shouldNotViolate(
      app,
      "/fetch(1)",
      "URI has invalid syntax."
    )

    shouldViolate(app.copy(resources = Resources(mem = -3.0)), "/mem", "got -3.0, expected 0.0 or more")
    shouldViolate(app.copy(resources = Resources(cpus = -3.0)), "/cpus", "got -3.0, expected 0.0 or more")
    shouldViolate(app.copy(resources = Resources(disk = -3.0)), "/disk", "got -3.0, expected 0.0 or more")
    shouldViolate(app.copy(resources = Resources(gpus = -3)), "/gpus", "got -3, expected 0 or more")
    shouldViolate(app.copy(instances = -3), "/instances", "got -3, expected 0 or more")

    shouldViolate(app.copy(resources = Resources(gpus = 1)), "/", "Feature gpu_resources is not enabled. Enable with --enable_features gpu_resources)")

    {
      implicit val appValidator = AppDefinition.validAppDefinition(Set("gpu_resources"))(PluginManager.None)
      shouldNotViolate(app.copy(resources = Resources(gpus = 1)), "/", "Feature gpu_resources is not enabled. Enable with --enable_features gpu_resources)")(appValidator)
    }

    app = correct.copy(
      resources = Resources(gpus = 1),
      container = Some(Container.Docker())
    )

    shouldViolate(app, "/", "GPU resources only work with the Mesos containerizer")

    app = correct.copy(
      resources = Resources(gpus = 1),
      container = Some(Container.Mesos())
    )

    shouldNotViolate(app, "/", "GPU resources only work with the Mesos containerizer")

    app = correct.copy(
      resources = Resources(gpus = 1),
      container = Some(Container.MesosDocker())
    )

    shouldNotViolate(app, "/", "GPU resources only work with the Mesos containerizer")

    app = correct.copy(
      resources = Resources(gpus = 1),
      container = Some(Container.MesosAppC())
    )

    shouldNotViolate(app, "/", "GPU resources only work with the Mesos containerizer")

    app = correct.copy(
      resources = Resources(gpus = 1),
      container = None
    )

    shouldNotViolate(app, "/", "GPU resources only work with the Mesos containerizer")
  }

  test("SerializationRoundtrip empty") {
    import Formats._
    val app1 = AppDefinition(id = PathId("/test"))
    assert(app1.cmd.isEmpty)
    assert(app1.args.isEmpty)
    JsonTestHelper.assertSerializationRoundtripWorks(app1)
  }

  private[this] def fromJson(json: String): AppDefinition = {
    import Formats._
    Json.fromJson[AppDefinition](Json.parse(json)).getOrElse(throw new RuntimeException(s"could not parse: $json"))
  }

  test("Reading app definition with command health check") {
    val json2 =
      """
      {
        "id": "toggle",
        "cmd": "python toggle.py $PORT0",
        "cpus": 0.2,
        "disk": 0.0,
        "healthChecks": [
          {
            "protocol": "COMMAND",
            "command": { "value": "env && http http://$HOST:$PORT0/" }
          }
        ],
        "instances": 2,
        "mem": 32.0,
        "ports": [0],
        "uris": ["http://downloads.mesosphere.com/misc/toggle.tgz"]
      }
      """
    val readResult2 = fromJson(json2)
    assert(readResult2.healthChecks.head == MesosCommandHealthCheck(command = Command("env && http http://$HOST:$PORT0/")))
  }

  test("SerializationRoundtrip with complex example") {
    import Formats._

    val app3 = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      user = Some("nobody"),
      env = EnvVarValue(Map("key1" -> "value1", "key2" -> "value2")),
      instances = 5,
      resources = Resources(cpus = 5.0, mem = 55.0, disk = 550.0),
      executor = "",
      constraints = Set(
        Constraint.newBuilder
        .setField("attribute")
        .setOperator(Constraint.Operator.GROUP_BY)
        .setValue("value")
        .build
      ),
      storeUrls = Seq("http://my.org.com/artifacts/foo.bar"),
      portDefinitions = PortDefinitions(9001, 9002),
      requirePorts = true,
      backoffStrategy = BackoffStrategy(
        backoff = 5.seconds,
        factor = 1.5,
        maxLaunchDelay = 3.minutes),
      container = Some(Docker(image = "group/image")),
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))),
      dependencies = Set(PathId("/prod/product/backend")),
      upgradeStrategy = UpgradeStrategy(minimumHealthCapacity = 0.75)
    )
    JsonTestHelper.assertSerializationRoundtripWorks(app3)
  }

  test("SerializationRoundtrip preserves portIndex") {
    import Formats._

    val app3 = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      portDefinitions = PortDefinitions(9001, 9002),
      healthChecks = Set(MarathonHttpHealthCheck(portIndex = Some(PortReference(1))))
    )
    JsonTestHelper.assertSerializationRoundtripWorks(app3)
  }

  test("Reading AppDefinition adds portIndex to a Marathon HTTP health check if the app has ports") {
    import Formats._

    val app = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      portDefinitions = PortDefinitions(9001, 9002),
      healthChecks = Set(MarathonHttpHealthCheck())
    )

    val json = Json.toJson(app)
    val reread = Json.fromJson[AppDefinition](json).get

    reread.healthChecks.headOption should be(Some(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))))
  }

  test("Reading AppDefinition does not add portIndex to a Marathon HTTP health check if the app doesn't have ports") {
    import Formats._

    val app = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      portDefinitions = Seq.empty,
      healthChecks = Set(MarathonHttpHealthCheck())
    )

    val json = Json.toJson(app)
    val reread = Json.fromJson[AppDefinition](json).get

    reread.healthChecks.headOption should be(Some(MarathonHttpHealthCheck(portIndex = None)))
  }

  test("Reading AppDefinition adds portIndex to a Marathon HTTP health check if it has at least one portMapping") {
    import Formats._

    val app = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      portDefinitions = Seq.empty,
      container = Some(
        Docker(
          network = Some(mesos.ContainerInfo.DockerInfo.Network.USER),
          portMappings = Seq(Container.PortMapping(containerPort = 1))
        )
      ),
      healthChecks = Set(MarathonHttpHealthCheck())
    )

    val json = Json.toJson(app)
    val parsedApp = Json.fromJson[AppDefinition](json)
    withClue(s"json ${json}\n but parsed ${parsedApp}") {
      parsedApp.asOpt.nonEmpty should be(true)
      parsedApp.asOpt.foreach { reread =>
        reread.healthChecks.headOption should be(Some(MarathonHttpHealthCheck(portIndex = Some(PortReference(0)))))
      }
    }
  }

  test("Reading AppDefinition adds not add portIndex to a Marathon HTTP health check if it has no ports nor portMappings") {
    import Formats._

    val app = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      portDefinitions = Seq.empty,
      container = Some(Docker()),
      healthChecks = Set(MarathonHttpHealthCheck())
    )

    val json = Json.toJson(app)
    val reread = Json.fromJson[AppDefinition](json).get

    reread.healthChecks.headOption should be(Some(MarathonHttpHealthCheck(portIndex = None)))
  }

  test("Reading AppDefinition does not add portIndex to a Mesos HTTP health check if the app doesn't have ports") {
    import Formats._

    val app = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      portDefinitions = Seq.empty,
      healthChecks = Set(MesosHttpHealthCheck())
    )

    val json = Json.toJson(app)
    val reread = Json.fromJson[AppDefinition](json).get

    reread.healthChecks.headOption should be(Some(MesosHttpHealthCheck(portIndex = None)))
  }

  test("Reading AppDefinition adds portIndex to a Mesos HTTP health check if it has at least one portMapping") {
    import Formats._

    val app = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      portDefinitions = Seq.empty,
      container = Some(
        Docker(
          network = Some(mesos.ContainerInfo.DockerInfo.Network.USER),
          portMappings = Seq(Container.PortMapping(containerPort = 1))
        )
      ),
      healthChecks = Set(MesosHttpHealthCheck())
    )

    val json = Json.toJson(app)
    val reread = Json.fromJson[AppDefinition](json).get

    reread.healthChecks.headOption should be(Some(MesosHttpHealthCheck(portIndex = Some(PortReference(0)))))
  }

  test("Reading AppDefinition does not add portIndex to a Mesos HTTP health check if it has no ports nor portMappings") {
    import Formats._

    val app = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      portDefinitions = Seq.empty,
      container = Some(Docker()),
      healthChecks = Set(MesosHttpHealthCheck())
    )

    val json = Json.toJson(app)
    val reread = Json.fromJson[AppDefinition](json).get

    reread.healthChecks.headOption should be(Some(MesosHttpHealthCheck(portIndex = None)))
  }

  test("Read app with container definition and port mappings") {
    import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network

    val app4 = AppDefinition(
      id = "bridged-webapp".toPath,
      cmd = Some("python3 -m http.server 8080"),
      container = Some(Docker(
        image = "python:3",
        network = Some(Network.BRIDGE),
        portMappings = Seq(
          PortMapping(containerPort = 8080, hostPort = Some(0), servicePort = 9000, protocol = "tcp")
        )
      ))
    )

    val json4 =
      """
      {
        "id": "bridged-webapp",
        "cmd": "python3 -m http.server 8080",
        "container": {
          "type": "DOCKER",
          "docker": {
            "image": "python:3",
            "network": "BRIDGE",
            "portMappings": [
              { "containerPort": 8080, "hostPort": 0, "servicePort": 9000, "protocol": "tcp" }
            ]
          }
        }
      }
      """
    val readResult4 = fromJson(json4)

    assert(readResult4.copy(versionInfo = app4.versionInfo) == app4)
  }

  test("Read app with fetch definition") {

    val app = AppDefinition(
      id = "app-with-fetch".toPath,
      cmd = Some("brew update"),
      fetch = Seq(
        new FetchUri(uri = "http://example.com/file1", executable = false, extract = true, cache = true,
          outputFile = None),
        new FetchUri(uri = "http://example.com/file2", executable = true, extract = false, cache = false,
          outputFile = None)
      )
    )

    val json =
      """
      {
        "id": "app-with-fetch",
        "cmd": "brew update",
        "fetch": [
          {
            "uri": "http://example.com/file1",
            "executable": false,
            "extract": true,
            "cache": true
          },
          {
            "uri": "http://example.com/file2",
            "executable": true,
            "extract": false,
            "cache": false
          }
        ]
      }
      """
    val readResult = fromJson(json)
    assert(readResult.copy(versionInfo = app.versionInfo) == app)
  }

  test("Transfer uris to fetch") {
    val json =
      """
      {
        "id": "app-with-fetch",
        "cmd": "brew update",
        "uris": ["http://example.com/file1.tar.gz", "http://example.com/file"]
      }
      """

    val app = fromJson(json)

    assert(app.fetch(0).uri == "http://example.com/file1.tar.gz")
    assert(app.fetch(0).extract)

    assert(app.fetch(1).uri == "http://example.com/file")
    assert(!app.fetch(1).extract)

  }

  test("Serialize deserialize path with fetch") {
    val app = AppDefinition(
      id = "app-with-fetch".toPath,
      cmd = Some("brew update"),
      fetch = Seq(
        new FetchUri(uri = "http://example.com/file1", executable = false, extract = true, cache = true,
          outputFile = None),
        new FetchUri(uri = "http://example.com/file2", executable = true, extract = false, cache = false,
          outputFile = None)
      )
    )

    val proto = app.toProto

    val deserializedApp = AppDefinition.fromProto(proto)

    assert(deserializedApp.fetch(0).uri == "http://example.com/file1")
    assert(deserializedApp.fetch(0).extract)
    assert(!deserializedApp.fetch(0).executable)
    assert(deserializedApp.fetch(0).cache)

    assert(deserializedApp.fetch(1).uri == "http://example.com/file2")
    assert(!deserializedApp.fetch(1).extract)
    assert(deserializedApp.fetch(1).executable)
    assert(!deserializedApp.fetch(1).cache)
  }

  test("Read app with ip address and discovery info") {
    val app = AppDefinition(
      id = "app-with-ip-address".toPath,
      cmd = Some("python3 -m http.server 8080"),
      portDefinitions = Nil,
      ipAddress = Some(IpAddress(
        groups = Seq("a", "b", "c"),
        labels = Map(
          "foo" -> "bar",
          "baz" -> "buzz"
        ),
        discoveryInfo = DiscoveryInfo(
          ports = Seq(Port(name = "http", number = 80, protocol = "tcp"))
        )
      )),
      backoffStrategy = BackoffStrategy(maxLaunchDelay = 3600.seconds)
    )

    val json =
      """
      {
        "id": "app-with-ip-address",
        "cmd": "python3 -m http.server 8080",
        "ipAddress": {
          "groups": ["a", "b", "c"],
          "labels": {
            "foo": "bar",
            "baz": "buzz"
          },
          "discovery": {
            "ports": [
              { "name": "http", "number": 80, "protocol": "tcp" }
            ]
          }
        },
        "maxLaunchDelaySeconds": 3600
      }
      """

    val readResult = fromJson(json)

    assert(readResult.copy(versionInfo = app.versionInfo) == app)
  }

  test("Read app with ip address without discovery info") {
    val app = AppDefinition(
      id = "app-with-ip-address".toPath,
      cmd = Some("python3 -m http.server 8080"),
      portDefinitions = Nil,
      ipAddress = Some(IpAddress(
        groups = Seq("a", "b", "c"),
        labels = Map(
          "foo" -> "bar",
          "baz" -> "buzz"
        ),
        discoveryInfo = DiscoveryInfo.empty
      )),
      backoffStrategy = BackoffStrategy(maxLaunchDelay = 3600.seconds)
    )

    val json =
      """
      {
        "id": "app-with-ip-address",
        "cmd": "python3 -m http.server 8080",
        "ipAddress": {
          "groups": ["a", "b", "c"],
          "labels": {
            "foo": "bar",
            "baz": "buzz"
          }
        }
      }
      """

    val readResult = fromJson(json)

    assert(readResult.copy(versionInfo = app.versionInfo) == app)
  }

  test("Read app with ip address and an empty ports list") {
    val app = AppDefinition(
      id = "app-with-network-isolation".toPath,
      cmd = Some("python3 -m http.server 8080"),
      portDefinitions = Nil,
      ipAddress = Some(IpAddress())
    )

    val json =
      """
      {
        "id": "app-with-network-isolation",
        "cmd": "python3 -m http.server 8080",
        "ports": [],
        "ipAddress": {}
      }
      """

    val readResult = fromJson(json)

    assert(readResult.copy(versionInfo = app.versionInfo) == app)
  }

  test("App may not have non-empty ports and ipAddress") {
    val json =
      """
      {
        "id": "app-with-network-isolation",
        "cmd": "python3 -m http.server 8080",
        "ports": [0],
        "ipAddress": {
          "groups": ["a", "b", "c"],
          "labels": {
            "foo": "bar",
            "baz": "buzz"
          }
        }
      }
      """

    import Formats._
    val result = Json.fromJson[AppDefinition](Json.parse(json))
    assert(result == JsError(ValidationError("You cannot specify both an IP address and ports")))
  }

  test("App may not have both uris and fetch") {
    val json =
      """
      {
        "id": "app-with-network-isolation",
        "uris": ["http://example.com/file1.tar.gz"],
        "fetch": [{"uri": "http://example.com/file1.tar.gz"}]
      }
      """

    import Formats._
    val result = Json.fromJson[AppDefinition](Json.parse(json))
    assert(result == JsError(ValidationError("You cannot specify both uris and fetch fields")))
  }

  test("Residency serialization (toProto) and deserialization (fromProto)") {
    val app = AppDefinition(
      id = "/test".toRootPath,
      residency = Some(Residency(
        relaunchEscalationTimeoutSeconds = 3600,
        taskLostBehavior = Protos.ResidencyDefinition.TaskLostBehavior.WAIT_FOREVER)))
    val proto = app.toProto

    proto.hasResidency shouldBe true
    proto.getResidency.getRelaunchEscalationTimeoutSeconds shouldBe 3600
    proto.getResidency.getTaskLostBehavior shouldBe Protos.ResidencyDefinition.TaskLostBehavior.WAIT_FOREVER

    val appAgain = AppDefinition.fromProto(proto)
    appAgain.residency should not be empty
    appAgain.residency.get.relaunchEscalationTimeoutSeconds shouldBe 3600
    appAgain.residency.get.taskLostBehavior shouldBe Protos.ResidencyDefinition.TaskLostBehavior.WAIT_FOREVER
  }

  ignore("app with readinessCheck passes validation") {
    val app = AppDefinition(
      id = "/test".toRootPath,
      cmd = Some("sleep 1234"),
      readinessChecks = Seq(
        ReadinessCheckTestHelper.alternativeHttps
      )
    )

    MarathonTestHelper.validateJsonSchema(app)
  }

  test("SerializationRoundtrip preserves secret references in environment variables") {
    import Formats._

    val app3 = AppDefinition(
      id = PathId("/prod/product/frontend/my-app"),
      cmd = Some("sleep 30"),
      env = Map[String, EnvVarValue](
        "foo" -> "bar".toEnvVar,
        "qaz" -> EnvVarSecretRef("james")
      )
    )
    JsonTestHelper.assertSerializationRoundtripWorks(app3)
  }

  test("environment variables with secrets should parse") {
    val json =
      """
      {
        "id": "app-with-network-isolation",
        "cmd": "python3 -m http.server 8080",
        "env": {
          "qwe": "rty",
          "ssh": { "secret": "psst" }
        }
      }
      """

    import Formats._
    val result = Json.fromJson[AppDefinition](Json.parse(json))
    assert(result.get.env.equals(Map[String, EnvVarValue](
      "qwe" -> "rty".toEnvVar,
      "ssh" -> EnvVarSecretRef("psst")
    )))
  }

  test("container port mappings when empty stays empty") {
    val appDef = AppDefinition(id = PathId("/test"), container = Some(Docker()))
    val roundTripped = AppDefinition.fromProto(appDef.toProto)
    roundTripped should equal(appDef)
    roundTripped.container.map(_.portMappings) should equal(appDef.container.map(_.portMappings))
  }
}
