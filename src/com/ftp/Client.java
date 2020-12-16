package com.ftp;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;

class Client {
    // Connection details
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    private Client(Socket socket, DataInputStream input, DataOutputStream output) {
        this.socket = socket;
        this.in = input;
        this.out = output;
    }

    // Returns 1 or -1 based on the server response
    // If an exception occurs then 0 is returned
    public int deleteRequest(String filename) {
        try {
            // Send operation and filename
            Log.log("Sending DELF operation to server");
            out.writeUTF("DELF");
            out.writeShort(filename.length());
            out.writeChars(filename);

            // Wait for server response
            int response = in.readInt();

            if (response == 1) {
                // Log the fact that the file exists
                Log.log("File exists on the server");
                return response;
            } else if (response == -1) {
                Log.log("File does not exist on server");
                return response;
            } else {
                Log.log("Unknown value returned for whether the file exists (" + response + ")");
                return 0;
            }

        } catch (IOException e) {
            // Handle error
            Log.log(e.getMessage());
            return 0;
        }
    }

    // Returns true if everything went well without any server/socket errors
    public boolean deleteConfirm(boolean delete) {
        try {
            // The client sends the users confirm status
            out.writeBoolean(delete);

            // Either display the servers response, or display the cancellation
            if (delete) {
                Log.log(in.readUTF());
            } else {
                Log.log("Delete abandoned by the user");
            }
        } catch (IOException e) {
            // Handle errors
            Log.log("Error sending confirmation input to server");
            Log.log(e.getMessage());
            return false;
        }

        return true;
    }

    // Returns an object representing the file downloaded
    // This object also indicates whether or not errors occurred
    public DownloadedFile download(String filename) {
        long startTime = System.currentTimeMillis();

        // Download bytes from server
        byte[] bytes;
        try {
            bytes = downloadFromServer(filename);
        } catch (IOException e) {
            // Handle errors, errors here should cause a disconnect
            Log.log(e.getMessage());
            return new DownloadedFile(true, null);
        }

        // Gather statistics
        if (bytes != null) {
            long endTime = System.currentTimeMillis();
            double timeTaken = (endTime - startTime);
            timeTaken /= 1000;
            Log.log(String.format("%,d bytes transferred in %,.2fs", bytes.length, timeTaken));
        }

        // If bytes is null then some error has occurred, but it's not fatal
        return new DownloadedFile(false, bytes);
    }

    private byte[] downloadFromServer(String filename) throws IOException {
        // Send operation and filename
        Log.log("Sending DWLD operation to server");
        out.writeUTF("DWLD");
        out.writeShort(filename.length());
        out.writeChars(filename);

        // Read server response, handle weird values (out of spec)
        int fileSize = in.readInt();
        if (fileSize == -1) {
            Log.log("File does not exist on server");
            return null;
        } else if (fileSize < 0) {
            Log.log("Negative integer returned for filesize that was not -1. Download cancelled");
            return null;
        }

        // Confirm readiness to download
        out.writeBoolean(true);
        Log.log("Downloading from server");

        // Declare our array of bytes
        byte[] bytes = new byte[fileSize];
        int totBytesRead = 0;

        // Read as many bytes as possible until buffer is full
        while (totBytesRead < fileSize) {
            int bytesRead = in.read(bytes, totBytesRead, fileSize - totBytesRead);
            totBytesRead += bytesRead;
        }

        return bytes;
    }

    // Returns true if everything went well without any server/socket errors
    public boolean list() {
        String[] listings;

        try {
            listings = retrieveListings();
        } catch (IOException e) {
            // Handle errors
            Log.log(e.getMessage());
            e.printStackTrace();
            return false;
        }

        // Once listings are retrieved, display to client (if there is at least one listing
        if (listings != null) {

            Log.log("Listings:");
            for (String listing : listings) {
                Log.log(listing);
            }
        }

        return true;
    }

    private String[] retrieveListings() throws IOException {
        // Send operation
        Log.log("Retrieving listings");
        out.writeUTF("LIST");

        // Number of listings to retrieve
        int numListings = in.readInt();
        if (numListings <= 0) {
            Log.log("Server contains no listings");
            return null;
        }

        // Retrieve listings
        String[] listings = new String[numListings];
        for (int i = 0; i < numListings; i++) {
            listings[i] = in.readUTF();
        }

        return listings;
    }

    // Attempts to quit gracefully using operations
    public void quit() {
        try {
            out.writeUTF("QUIT");
            out.close();
            in.close();
            socket.close();
        } catch (IOException e) {
            Log.log("Error quitting gracefully (" + e.getMessage() + ")");
            Log.log("Force closing");

            // Force close
            try { out.close(); } catch (IOException f) { /* Do nothing */ }
            try { in.close(); } catch (IOException f) { /* Do nothing */ }
            try { socket.close(); } catch (IOException f) { /* Do nothing */ }

        }
        Log.log("Session closed");
    }

    // Returns false if there is a SERVER error
    // Client errors (eg. IOException on file read, will still return true)
    public boolean upload(File file, String filename)  {
        // Read the file as bytes from disk first
        Log.log("Reading file from disk");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            // Handle errors. Errors reading file are not fatal to the server-client connection
            Log.log(e.getMessage());
            e.printStackTrace();
            return true;
        }

        // Send the file to the server
        try {
            uploadFile(filename, bytes);
        } catch (IOException e) {
            // Handle errors
            Log.log(e.getMessage());
            e.printStackTrace();
            return false;
        }

        return true;
    }

    // The code that performs the upload (wrapped in upload to handle errors)
    private void uploadFile(String filename, byte[] bytes) throws IOException {
        // Send operation, filename, and length of file
        Log.log("Sending UPLD operation to server and waiting for response");
        out.writeUTF("UPLD");
        out.writeShort(filename.length());
        out.writeChars(filename);
        out.writeInt(bytes.length);

        // Get server confirmation
        if (!in.readBoolean()) {
            String reason = in.readUTF();
            Log.log("Server rejected request");
            Log.log("Reason: " + reason);
            return;
        }

        // Send file
        Log.log("Sending data to server");
        out.write(bytes);
        Log.log(in.readUTF());
    }


    // Factory method to create a client instance
    public static Client connect(String ip, int port, int timeout) {
        try {
            Log.log("Connecting to server");
            Socket socket = new Socket(ip, port);
            socket.setSoTimeout(timeout);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            Log.log("Connected");

            return new Client(socket, in, out);
        } catch (IOException e) {
            // Handle errors
            Log.log(e.getMessage());
            return null;
        }
    }
}
