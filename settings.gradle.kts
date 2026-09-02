rootProject.name = "skyengine-z"

include("skyengine-shared", "skyengine-gameplay", "skyengine-server", "skyengine-client", "skyengine-tools")

project(":skyengine-shared").projectDir = file("shared")
project(":skyengine-gameplay").projectDir = file("gameplay")
project(":skyengine-server").projectDir = file("server")
project(":skyengine-client").projectDir = file("client")
project(":skyengine-tools").projectDir = file("tools")
