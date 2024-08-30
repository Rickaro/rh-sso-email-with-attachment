package org.keycloak.email;

import com.sun.mail.smtp.SMTPMessage;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.services.ServicesLogger;
import org.keycloak.theme.Theme;
import org.keycloak.theme.ThemeProvider;
import org.keycloak.common.enums.HostnameVerificationPolicy;
import org.keycloak.truststore.JSSETruststoreConfigurator;
import org.keycloak.vault.VaultStringSecret;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.*;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.UnsupportedEncodingException;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.net.URLConnection;
import java.net.FileNameMap;
import java.nio.file.Files;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.keycloak.utils.StringUtil.isNotBlank;

public class EmailWithAttachmentSenderProvider implements EmailSenderProvider {
    private static final Logger logger = Logger.getLogger(DefaultEmailSenderProvider.class);
    private static final String SUPPORTED_SSL_PROTOCOLS = getSupportedSslProtocols();

    private final KeycloakSession session;

    public EmailWithAttachmentSenderProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void send(Map<String, String> config, UserModel user, String subject, String textBody, String htmlBody) throws EmailException {
        send(config, retrieveEmailAddress(user), subject, textBody, htmlBody);
    }

    @Override
    public void send(Map<String, String> config, String address, String subject, String textBody, String htmlBody) throws EmailException {
        Transport transport = null;
        try {

            Properties props = new Properties();

            if (config.containsKey("host")) {
                props.setProperty("mail.smtp.host", config.get("host"));
            }

            boolean auth = "true".equals(config.get("auth"));
            boolean ssl = "true".equals(config.get("ssl"));
            boolean starttls = "true".equals(config.get("starttls"));

            if (config.containsKey("port") && config.get("port") != null) {
                props.setProperty("mail.smtp.port", config.get("port"));
            }

            if (auth) {
                props.setProperty("mail.smtp.auth", "true");
            }

            if (ssl) {
                props.setProperty("mail.smtp.ssl.enable", "true");
            }

            if (starttls) {
                props.setProperty("mail.smtp.starttls.enable", "true");
            }

	    if (ssl || starttls || auth){
                props.put("mail.smtp.ssl.protocols", SUPPORTED_SSL_PROTOCOLS);

                setupTruststore(props);
            }

            props.setProperty("mail.smtp.timeout", "10000");
            props.setProperty("mail.smtp.connectiontimeout", "10000");

            String from = config.get("from");
            String fromDisplayName = config.get("fromDisplayName");
            String replyTo = config.get("replyTo");
            String replyToDisplayName = config.get("replyToDisplayName");
            String envelopeFrom = config.get("envelopeFrom");

            Session session = Session.getInstance(props);

            Multipart multipart = new MimeMultipart("alternative");

            if (textBody != null) {
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(textBody, "UTF-8");
                multipart.addBodyPart(textPart);
            }

            if (htmlBody != null) {
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");
                multipart.addBodyPart(htmlPart);
               
                Theme theme = this.session.theme().getTheme(Theme.Type.EMAIL); 

                final Matcher m = Pattern.compile("(?s)(\\\"cid:([^\\\"]+)\\\")(?!.*\\1.*)").matcher(htmlBody);
                while (m.find()) {
                   String attach = m.group(2);
                   logger.warn("Cid found: " + attach); 
                   addResource(multipart, theme, attach);
                }
            }

            SMTPMessage msg = new SMTPMessage(session);
            msg.setFrom(toInternetAddress(from, fromDisplayName));

            msg.setReplyTo(new Address[]{toInternetAddress(from, fromDisplayName)});

            if (isNotBlank(replyTo)) {
                msg.setReplyTo(new Address[]{toInternetAddress(replyTo, replyToDisplayName)});
            }
            if (isNotBlank(envelopeFrom)) {
                msg.setEnvelopeFrom(envelopeFrom);
            }

            msg.setHeader("To", address);
            msg.setSubject(subject, "utf-8");
            msg.setContent(multipart);
            msg.saveChanges();
            msg.setSentDate(new Date());

            transport = session.getTransport("smtp");
            if (auth) {
		try (VaultStringSecret vaultStringSecret = this.session.vault().getStringSecret(config.get("password"))) {
                    transport.connect(config.get("user"), vaultStringSecret.get().orElse(config.get("password")));
                }
            } else {
                transport.connect();
            }
            transport.sendMessage(msg, new InternetAddress[]{new InternetAddress(address)});
        } catch (Exception e) {
            ServicesLogger.LOGGER.failedToSendEmail(e);
            throw new EmailException(e);
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException e) {
                    logger.warn("Failed to close transport", e);
                }
            }
        }
    }

    protected InternetAddress toInternetAddress(String email, String displayName) throws UnsupportedEncodingException, AddressException, EmailException {
        if (email == null || "".equals(email.trim())) {
            throw new EmailException("Please provide a valid address", null);
        }
        if (displayName == null || "".equals(displayName.trim())) {
            return new InternetAddress(email);
        }
        return new InternetAddress(email, displayName, "utf-8");
    }

    protected String retrieveEmailAddress(UserModel user) {
        return user.getEmail();
    }

    private void setupTruststore(Properties props) throws NoSuchAlgorithmException, KeyManagementException {

        JSSETruststoreConfigurator configurator = new JSSETruststoreConfigurator(session);

        SSLSocketFactory factory = configurator.getSSLSocketFactory();
        if (factory != null) {
            props.put("mail.smtp.ssl.socketFactory", factory);
            if (configurator.getProvider().getPolicy() == HostnameVerificationPolicy.ANY) {
                props.setProperty("mail.smtp.ssl.trust", "*");
                props.put("mail.smtp.ssl.checkserveridentity", Boolean.FALSE.toString()); // this should be the default but seems to be impl specific, so set it explicitly just to be sure
            }
            else {
                props.put("mail.smtp.ssl.checkserveridentity", Boolean.TRUE.toString());
            }
        }	
    }

    @Override
    public void close() {

    }

    private static String getSupportedSslProtocols() {
        try {
            String[] protocols = SSLContext.getDefault().getSupportedSSLParameters().getProtocols();
            if (protocols != null) {
                return String.join(" ", protocols);
            }
        } catch (Exception e) {
            logger.warn("Failed to get list of supported SSL protocols", e);
        }
        return null;
    }

    private void addAttachment(Multipart multipart, Theme theme, String path, String contentId) {
        logger.debug("addAttachment:" + path);

        // Open stream twice so javax.mailer can get content type
        try {
          String fileName = new File(path).toPath().getFileName().toString();
          logger.debug("addAttachment filename:" + fileName + ", path: " + path);

          InputStream is1 = theme.getResourceAsStream(path);
          InputStream is2 = theme.getResourceAsStream(path);

          if (is1 == null || is2 == null) {
            logger.warn("Attach stream is null: " + path);
          } else {
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setDataHandler(new DataHandler(new InputStreamDataSource( is1, is2, fileName )));

            htmlPart.setFileName(fileName);
            htmlPart.setHeader("Content-ID", "<" + contentId + ">");
            htmlPart.setDisposition(MimeBodyPart.INLINE);
            multipart.addBodyPart(htmlPart);
          }
        } catch (MessagingException | IOException e) {
            logger.warn("Failed to attach file: " + path, e);
        }
    }

    private void addResource(Multipart multipart, Theme theme, String contentId) {
        try {
          Properties properties = theme.getProperties();

          String path = properties.getProperty("attach_" + contentId);
          if (path != null) {
              addAttachment(multipart, theme, path, contentId);
          } else {
            logger.warn("Propertie attach_" + contentId + " not found in theme");
          }
        } catch (IOException e) {
            logger.warn("Failed to get theme properties", e);
        }
    }
}
