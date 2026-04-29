build:
	javac GameClient.java
	erlc server.erl

server:
	erl

player:
	javac GameClient.java
	java GameClient

clean:
	rm -f *.class *.beam