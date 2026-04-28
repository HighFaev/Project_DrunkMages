# Java Royale

Tiny networking smoke-test before the real lobby goes in: a Netty client opens a TCP
connection, sends its nickname, and prints back the id the server hands out.

## Build

From the project root:

```powershell
mvn clean install
```

## Run Server

Terminal 1:

```powershell
mvn -pl networking exec:java "-Dexec.mainClass=io.github.drunkmages.networking.NetworkServer"
```

Default port is `25565`.

## Run Client

Terminal 2:

```powershell
mvn -pl networking test-compile exec:java "-Dexec.mainClass=io.github.drunkmages.networking.NetworkClientTest" "-Dexec.args=127.0.0.1 25565 nick"
```

Arguments are:

```text
host port nickname
```

Example server output:

```text
server listening on 25565
joined nick as 1
```

Example client output:

```text
connected 127.0.0.1:25565 nick=nick
welcome id=1
done
```

If client shows `Connection refused`, server is not running or the port is wrong.
