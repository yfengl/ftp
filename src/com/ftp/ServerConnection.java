package com.ftp;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ServerConnection implements Runnable{
    private int id;

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;

    ServerConnection(Socket clientSocket, int id) {
        this.socket = clientSocket;
        this.id = id;
    }

    public void run() {
        log("Client connected");

        try {
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());

            try {
                mainLoop();
            } catch (ClientError e) {
                log(e.getMessage());

                if (e.sendErrorBack) {
                    log("Sending error message back to client");
                    output.writeBoolean(false);
                    output.writeUTF(e.getMessage());
                }

                log("Attempting to end connection gracefully");
            }

            output.close();
            input.close();
            socket.close();
        } catch (IOException e) {
            log("Input/Output error occurred: " + e.getMessage());
            log("Closing client connection forcefully");

            try { output.close(); } catch (IOException f) { /* Do nothing */ }
            try { input.close(); } catch (IOException f) { /* Do nothing */ }
            try { socket.close(); } catch (IOException f) { /* Do nothing */ }
        }

        log("Client disconnected");
    }

    private void mainLoop() throws IOException, ClientError {
        wait: while (true) {
            String operation;
            try {
                operation = input.readUTF();
            } catch (SocketTimeoutException e) {
                // Socket will continually timeout as no input is received unless prompted by the client
                continue;
            }

            switch (operation) {
                case "UPLD":
                    upload();
                    break;
                case "LIST":
                    list();
                    break;
                case "DWLD":
                    download();
                    break;
                case "DELF":
                    delete();
                    break;
                case "QUIT":
                    log("QUIT triggered by client");
                    break wait;
                default:
                    log("Operation unknown: " + operation);
                    log("Terminating connection due to client error");
                    break wait;
            }
        }
    }

    private void delete() throws IOException, ClientError {
        log("Client is requesting to delete a file");

        // Receive filename and add base directory
        String filename = getFilename(false);
        String fullPath = filenameAddBaseDir(filename);

        // Server returns 1 or -1 based on whether or not the file exists
        File file = new File(fullPath);
        if (file.exists()) {
            log("Waiting for confirmation to delete: " + fullPath);
            output.writeInt(1);
        } else {
            log("File doesn't exist: " + fullPath);
            output.writeInt(-1);
            return;
        }

        // Wait for delete confirm to be sent by the client
        // True for confirm delete, false otherwise
        // Adjust socket timeout temporarily to give a 60s grace period
        socket.setSoTimeout(60 * 1000);
        boolean confirm = input.readBoolean();
        socket.setSoTimeout(Server.TIMEOUT);

        if (!confirm) {
            log("Client did not confirm file deletion");
            return;
        }

        // Delete file
        String msg;
        if (file.delete()) {
            msg = "File deleted";
        } else {
            msg = "Error deleting file";
        }

        log(msg);
        output.writeUTF(msg);
    }

    private void download() throws IOException, ClientError {
        log("Client is requesting to download a file");

        String filename = getFilename(false);
        String fullPath = filenameAddBaseDir(filename);

        // Check if file exists
        File file = new File(fullPath);
        if (!file.exists()) {
            log("The file \"" + filename + "\" does not exist on the server");
            output.writeInt(-1);
            return;
        }

        // Send the file size back to the client
        // Since we're limited to 32 bit integers for the file size, then this will cause the server to crash on files larger than 2^31 bytes
        output.writeInt((int) file.length());

        // Read file from disk while we wait for client to respond
        log("Reading file from disk");
        byte[] bytes = Files.readAllBytes(file.toPath());

        // Wait for client to return ready
        if (!input.readBoolean()) {
            log("Client returned false for ready status");
            return;
        }

        // Send bytes to client
        output.write(bytes);
        log("Bytes sent");
    }

    private void list() throws IOException {
        log("Sending listings to client");
        List<String> listings = new ArrayList<>();

        // Get listings by traversing through source directory
        Files.walk(Paths.get(Server.BASE_DIR))
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    String listing = path.toString().substring(Server.BASE_DIR.length());
                    listings.add(listing);
                });

        // Send listings to client
        output.writeInt(listings.size());
        for (String listing : listings) {
            output.writeUTF(listing);
        }

        log("Sent listings to client");
    }

    private void upload() throws IOException, ClientError {
        log("Client is requesting to upload a file");

        // Start timer
        long startTime = System.currentTimeMillis();

        String fileName = getFilename(true);
        String fullPath = filenameAddBaseDir(fileName);
        log("Filename: " + fileName);

        // Get filesize
        int fileSize = input.readInt();
        if (fileSize < 0) {
            throw new ClientError("File size is less than 0 (" + fileSize + ")", true);
        }
        log("Filesize: " + fileSize);

        // Receive data from client
        log("Ready to receive data");
        output.writeBoolean(true);

        // Declare our array of bytes
        byte[] bytes = new byte[fileSize];
        int totBytesRead = 0;

        // Read as many bytes as possible until buffer is full
        while (totBytesRead < fileSize) {
            int bytesRead = input.read(bytes, totBytesRead, fileSize - totBytesRead);
            totBytesRead += bytesRead;
        }

        // Write file out
        File outFile = new File(fullPath);
        //noinspection ResultOfMethodCallIgnored
        outFile.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(outFile)) {
            stream.write(bytes);
        } catch (IOException e) {
            log("Error writing file to disk");
            log(e.getMessage());
            output.writeUTF("Server error, could not write to disk (" + e.getMessage() + ")");
        }

        // Gather statistics
        long endTime = System.currentTimeMillis();
        double timeTaken = (endTime - startTime);
        timeTaken /= 1000;
        String response = String.format("%,d bytes transferred in %,.2fs", fileSize, timeTaken);

        log(response);
        output.writeUTF(response);
        log("Upload finished");
    }

    // Retrieves a filename in the form of short + char array
    // If a client error occurs during this, then sendErrorBack will determine how the client error is thrown
    private String getFilename(boolean sendErrorBack) throws IOException, ClientError {
        // Get length of filename
        short fileNameLen = input.readShort();
        if (fileNameLen < 1) {
            throw new ClientError("Length of filename was not a positive integer (received " + fileNameLen + ")", sendErrorBack);
        }

        // Read chars as filename
        char[] fileNameChar = new char[fileNameLen];
        for (int i = 0; i < fileNameLen; i++) {
            fileNameChar[i] = input.readChar();
        }

        return new String(fileNameChar);
    }

    private String filenameAddBaseDir(String filename) {
        return Server.BASE_DIR + filename;
    }

    private void log(String msg) {
        System.out.println("[Connection " + id + "] " + msg);
    }
}

class ClientError extends Exception {
    protected boolean sendErrorBack;

    ClientError(String message, boolean sendErrorBack) {
        super(message);
        this.sendErrorBack = sendErrorBack;
    }
}
