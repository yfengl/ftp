package com.ftp;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.function.UnaryOperator;

public class ClientController {
    private static String DEFAULT_IP = "localhost";
    private static int DEFAULT_PORT = 1234;
    private static int DEFAULT_TIMEOUT = 5000;
    public static String BASE_DIR = "client_files/";

    // Connection UI
    @FXML private TextField textIP;
    @FXML private TextField textPort;
    @FXML private TextField textTimeout;
    @FXML private Button connect;
    @FXML private Button quit;

    // Operations
    @FXML private Button delf;
    @FXML private Button dwld;
    @FXML private Button list;
    @FXML private Button upld;

    // Listview
    @FXML private ListView<String> listView;

    // Formatter to restrict inputs to only numbers
    // https://stackoverflow.com/q/40472668
    private UnaryOperator<TextFormatter.Change> integerFilter = textField -> {
        String input = textField.getText();
        if (input.matches("[0-9]*")) {
            return textField;
        }
        return null;
    };

    // Connection info
    private Client conn = null;

    @FXML
    public void initialize() {
        setUIState(false);

        textIP.setText(DEFAULT_IP);
        textPort.setText(String.valueOf(DEFAULT_PORT));
        textPort.setTextFormatter(new TextFormatter<String>(integerFilter));
        textTimeout.setText(String.valueOf(DEFAULT_TIMEOUT));
        textTimeout.setTextFormatter(new TextFormatter<String>(integerFilter));

        Log.init(listView);
    }

    private void setUIState() {
        setUIState(conn != null);
    }

    private void setUIState(boolean connected) {
        disableConnectionGUI(connected);
        disableOperations(!connected);
    }

    private void disableAllUI() {
        disableOperations(true);
        disableConnectionGUI(true);
    }

    private void disableConnectionGUI(boolean disable) {
        textIP.setDisable(disable);
        textPort.setDisable(disable);
        textTimeout.setDisable(disable);
        connect.setDisable(disable);
    }

    private void disableOperations(boolean disable) {
        quit.setDisable(disable);
        delf.setDisable(disable);
        dwld.setDisable(disable);
        list.setDisable(disable);
        upld.setDisable(disable);
    }

    @FXML
    private void connect() {
        // Get IP/Port
        String ip = textIP.getText();
        int port = Integer.parseInt(textPort.getText());
        int timeout = Integer.parseInt(textTimeout.getText());

        Task<Client> task = new Task<Client>() {
            @Override protected Client call() {
                return Client.connect(ip, port, timeout);
            }
        };

        task.setOnSucceeded(event -> {
            conn = task.getValue();
            setUIState();
        });

        startTask(task);
    }

    @FXML
    private void delete() {
        // Get the filename to delete
        Optional<String> result = getInput("Choose file to delete", "Enter filename:", "");
        if (!result.isPresent()) {
            return;
        }

        Task<Integer> task1 = new Task<Integer>() {
            @Override protected Integer call() {
                return conn.deleteRequest(result.get());
            }
        };

        task1.setOnSucceeded(event -> {
            // If response is 0 then a server/socket error occurred
            if (task1.getValue() == 0) {
                quit();
                return;
            } else if (task1.getValue() == -1) {
                setUIState();
                return;
            }

            // If the file exists then prompt user to delete
            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.setTitle("Confirm");
            a.setHeaderText("Delete file?");
            a.setContentText("Please note the server gives a 60s timeout to receive confirmation");
            Optional<ButtonType> result2 = a.showAndWait();

            // Create a new task to send the confirmation
            Task<Boolean> task2 = new Task<Boolean>() {
                @Override protected Boolean call() {
                    boolean confirm = result2.isPresent() && result2.get() == ButtonType.OK;
                    return conn.deleteConfirm(confirm);
                }
            };

            // Start the task
            updateOnTaskEnd(task2);
            startTask(task2);
        });

        startTask(task1);
    }

    @FXML
    private void download() {
        // Get filename
        Optional<String> result = getInput("Enter filename", "File to download from server:", "");
        if (!result.isPresent()) {
            return;
        }

        Task<DownloadedFile> task = new Task<DownloadedFile>() {
            @Override protected DownloadedFile call() {
                return conn.download(result.get());
            }
        };

        task.setOnSucceeded(event -> {
            DownloadedFile df = task.getValue();
            if (df.hadSocketError()) {
                quit();
                return;
            } else if (df.containsData()) {
                saveFile(result.get(), df.getData());
            }

            setUIState();
        });
        startTask(task);
    }

    @FXML
    private void list() {
        Task<Boolean> task = new Task<Boolean>() {
            @Override protected Boolean call() {
                return conn.list();
            }
        };

        updateOnTaskEnd(task);
        startTask(task);
    }

    @FXML
    private void quit() {
        conn.quit();
        conn = null;
        setUIState(false);
    }

    @FXML
    private void upload() {
        // Get file
        FileChooser fc = new FileChooser();
        fc.setTitle("Select file");
        fc.setInitialDirectory(new File(BASE_DIR));
        File file = fc.showOpenDialog(getStage());

        if (file == null) {
            return;
        }

        // Get filename
        Optional<String> result = getInput("Enter the filename to save on the server", "Filename:", file.getName());
        if (!result.isPresent()) {
            return;
        }

        Task<Boolean> task = new Task<Boolean>() {
            @Override protected Boolean call() {
                return conn.upload(file, result.get());
            }
        };

        updateOnTaskEnd(task);
        startTask(task);
    }

    private void saveFile(String suggestedName, byte[] data) {
        // Get file
        FileChooser fc = new FileChooser();
        fc.setTitle("Save file");
        fc.setInitialDirectory(new File(BASE_DIR));
        fc.setInitialFileName(new File(suggestedName).getName());
        File outFile = fc.showSaveDialog(getStage());

        if (outFile == null) {
            return;
        }

        // Make directory if it doesn't exist (for some reason)
        // Write data out
        //noinspection ResultOfMethodCallIgnored
        outFile.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(outFile)) {
            stream.write(data);
            Log.log("File saved to disk");
        } catch (IOException e) {
            Log.log("Error writing file to disk");
            Log.log(e.getMessage());
        }
    }

    private Stage getStage() {
        return (Stage) listView.getScene().getWindow();
    }

    private void updateOnTaskEnd(Task<Boolean> task) {
        task.setOnSucceeded(event -> {
            if (!task.getValue()) {
                quit();
            } else {
                setUIState();
            }
        });
    }

    private void startTask(Task task) {
        disableAllUI();
        Thread th = new Thread(task);
        th.setDaemon(true);
        th.start();
    }

    private Optional<String> getInput(String header, String content, String defaultValue) {
        TextInputDialog dialog = new TextInputDialog(defaultValue);
        dialog.initStyle(StageStyle.UTILITY);
        dialog.setTitle("");
        dialog.setHeaderText(header);
        dialog.setContentText(content);
        dialog.initOwner(getStage());
        dialog.initModality(Modality.WINDOW_MODAL);

        return dialog.showAndWait();

    }
}

class Log {
    private static ListView<String> list;

    public static void init(ListView<String> list) {
        Log.list = list;
    }

    public static void log(String msg) {
        System.out.println(msg);
        Platform.runLater(() -> {
            list.getItems().add(msg);
            list.scrollTo(list.getItems().size() - 1);
        });
    }
}

class DownloadedFile {
    private byte[] data;
    private boolean socketError;

    DownloadedFile(boolean socketError, byte[] data) {
        this.socketError = socketError;
        this.data = data;
    }

    public byte[] getData() {
        return data;
    }

    public boolean hadSocketError() {
        return socketError;
    }

    public boolean containsData() {
        return data != null;
    }
}
