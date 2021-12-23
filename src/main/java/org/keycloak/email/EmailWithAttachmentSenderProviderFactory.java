package org.keycloak.email;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class EmailWithAttachmentSenderProviderFactory implements EmailSenderProviderFactory {

    @Override
    public EmailSenderProvider create(KeycloakSession session) {
        return new EmailWithAttachmentSenderProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return "emailwithattachment";
    }

}
