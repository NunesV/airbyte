package io.airbyte.integrations.source.sftp;

import com.fasterxml.jackson.databind.JsonNode;
import com.jcraft.jsch.*;
import io.airbyte.integrations.source.sftp.enums.SftpAuthMethod;
import io.airbyte.integrations.source.sftp.enums.SupportedFileExtension;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Vector;

public class SftpClient {

    protected static final Logger LOGGER = LoggerFactory.getLogger(SftpClient.class);
    private static final String CHANNEL_SFTP = "sftp";
    private static final String STRICT_HOST_KEY_CHECKING = "StrictHostKeyChecking";

    private final String username;
    private final String hostAddress;
    private final int port;
    private final SftpAuthMethod authMethod;
    private final JsonNode config;
    private final int connectionTimeoutMillis = 60000;
    private final JSch jsch;
    private Session session;
    private ChannelSftp channelSftp;

    public SftpClient(JsonNode config) {
        this.config = config;
        JSch.setLogger(new LogAdapter());
        jsch = new JSch();
        username = config.has("user_name") ? config.get("user_name").asText() : "";
        hostAddress = config.has("host_address") ? config.get("host_address").asText() : "";
        port = config.has("port") ? config.get("port").asInt() : 22;
        authMethod = SftpAuthMethod.valueOf(config.get("credentials").get("auth_method").asText());
    }

    /**
     * Adapter from JSch's logger interface to our log4j
     */
    private static class LogAdapter implements com.jcraft.jsch.Logger {
        static final Log LOG = LogFactory.getLog(
                SftpClient.class.getName() + ".jsch");

        @Override
        public boolean isEnabled(int level) {
            switch (level) {
                case com.jcraft.jsch.Logger.DEBUG:
                    return LOG.isDebugEnabled();
                case com.jcraft.jsch.Logger.INFO:
                    return LOG.isInfoEnabled();
                case com.jcraft.jsch.Logger.WARN:
                    return LOG.isWarnEnabled();
                case com.jcraft.jsch.Logger.ERROR:
                    return LOG.isErrorEnabled();
                case com.jcraft.jsch.Logger.FATAL:
                    return LOG.isFatalEnabled();
                default:
                    return false;
            }
        }

        @Override
        public void log(int level, String message) {
            switch (level) {
                case com.jcraft.jsch.Logger.DEBUG:
                    LOG.debug(message);
                    break;
                case com.jcraft.jsch.Logger.INFO:
                    LOG.info(message);
                    break;
                case com.jcraft.jsch.Logger.WARN:
                    LOG.warn(message);
                    break;
                case com.jcraft.jsch.Logger.ERROR:
                    LOG.error(message);
                    break;
                case com.jcraft.jsch.Logger.FATAL:
                    LOG.fatal(message);
                    break;
                default:
                    break;
            }
        }
    }

    public void connect() {
        try {
            LOGGER.info("Connecting to the server");
            configureSession();
            configureAuthMethod();
            LOGGER.debug("Connecting to host: {} at port: {}", hostAddress, port);
            session.connect();
            Channel channel = session.openChannel(CHANNEL_SFTP);
            channel.connect();

            channelSftp = (ChannelSftp) channel;
            LOGGER.info("Connected successfully");
        } catch (Exception e) {
            LOGGER.error("Exception attempting to connect to the server:", e);
            throw new RuntimeException(e);
        }
    }

    private void configureSession() throws JSchException {
        Properties properties = new Properties();
        properties.put(STRICT_HOST_KEY_CHECKING, "no");
        session = jsch.getSession(username, hostAddress, port);
        session.setConfig(properties);
        session.setTimeout(connectionTimeoutMillis);
    }

    public void disconnect() {
        LOGGER.info("Disconnecting from the server");
        if (channelSftp != null) {
            channelSftp.disconnect();
        }
        if (session != null) {
            session.disconnect();
        }
        LOGGER.info("Disconnected successfully");
    }

    public boolean isConnected() {
        return channelSftp != null && channelSftp.isConnected();
    }

    public Vector lsFile(SupportedFileExtension fileExtension) {
        try {
            return channelSftp.ls("*." + fileExtension.typeName);
        } catch (SftpException e) {
            LOGGER.error("Exception occurred while trying to find files with type {} : ", fileExtension, e);
            throw new RuntimeException(e);
        }
    }

    public void changeWorkingDirectory(String path) throws SftpException {
        channelSftp.cd(path);
    }

    public ByteArrayInputStream getFile(String fileName) {
        try (InputStream inputStream = channelSftp.get(fileName)) {
            return new ByteArrayInputStream(IOUtils.toByteArray(inputStream));
        } catch (Exception e) {
            LOGGER.error("Exception occurred while trying to download file {} : ", fileName, e);
            throw new RuntimeException(e);
        }
    }

    private void configureAuthMethod() throws Exception {
        switch (authMethod) {
            case SSH_PASSWORD_AUTH -> session.setPassword(config.get("credentials").get("auth_user_password").asText());
            case SSH_KEY_AUTH -> {
                File tempFile = File.createTempFile("private_key", "", null);
                FileOutputStream fos = new FileOutputStream(tempFile);
                fos.write(config.get("credentials").get("auth_ssh_key").asText().getBytes(StandardCharsets.UTF_8));
                jsch.addIdentity(tempFile.getAbsolutePath());
            }
            default -> throw new RuntimeException("Unsupported SFTP Authentication type");
        }
    }

}
