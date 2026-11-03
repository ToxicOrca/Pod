# Copy to tools\publish.config.ps1 (git-ignored) and fill in.

# Public address of your Pod server (same as phones use).
$PublicUrl = "https://pod.example.com"

# The app ID your phones have installed (must match applicationId in android\keystore.properties).
$ExpectedAppId = "app.podfamily.location"

# Option A - remote: an SSH login on the server that may only install releases.
# The files are copied to <host>:upload/, then "$RemoteCommand <apk name>" runs over ssh.
$PublishHost = ""                  # e.g. "deploy@my-server.lan"
$RemoteCommand = ""                # e.g. "sudo /usr/local/sbin/pod-publish"

# Option B - local: the server's deploy/app folder, reachable from this PC.
$LocalAppDir = ""                  # e.g. "\\server\pod\deploy\app"
