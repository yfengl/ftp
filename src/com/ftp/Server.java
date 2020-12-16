package com.ftp;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {
    // Constants
    private final static int DEFAULT_PORT = 1234;
    private final static int DEFAULT_TIMEOUT = 5000;
    public final static String BASE_DIR = "server_files/";

    // Configurable shared run time constants (via command line)
    public static int TIMEOUT;

    private void run(int port, int timeout) {
        // Open port
        ServerSocket serverSocket;
        try {
            serverSocket = new ServerSocket(port);
        } catch (Exception e) {
            System.out.println("Couldn't open socket. " + e.getMessage());
            return;
        }
        System.out.println("Server started on port " + port + " with timeout " + timeout + "ms");

        int curID = 1;

        //noinspection InfiniteLoopStatement
        while(true){
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(timeout);
                new Thread(new ServerConnection(clientSocket, curID)).start();
                curID++;
            } catch (IOException e) {
                System.out.println("Error accepting client connection: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        // Create base dir if it doesn't exist
        File bd = new File(BASE_DIR);
        if (!bd.exists()) {
            System.out.println("Base directory '" + BASE_DIR + "' does not exist");
            if (bd.mkdir()) {
                System.out.println("Directory created");
            } else {
                System.out.println("Could not create directory. Errors will probably occur from now on");
            }
        }

        // Get user input for configurable options
        int port = parseCommandLineInteger(args, 0, "Port number must be a positive integer", DEFAULT_PORT);
        int timeout = parseCommandLineInteger(args, 1, "Timeout must be a positive integer (ms)", DEFAULT_TIMEOUT);

        // Run server
        new Server().run(port, timeout);
    }

    // Attempts to read a value from the command line as an integer
    // Returns the default value if this cannot be done
    private static int parseCommandLineInteger(String[] args, int index, String errMsg, int defaultVal) {
        // Return default value if argument not specified
        if (index >= args.length) {
            return defaultVal;
        }

        // Attempt to parse and return the int
        try {
            int val = Integer.parseInt(args[index]);

            if (val < 1) {
                System.out.println(errMsg);
                return defaultVal;
            }

            return val;
        } catch (NumberFormatException e) {
            System.out.println(errMsg);
            return defaultVal;
        }
    }
}
