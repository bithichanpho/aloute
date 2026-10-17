package com.aloute.service;

import com.aloute.model.document.AccountSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** One-time safe re-encryption pass when APP_SECURITY_ENCRYPTION_KEY is separated from JWT_SECRET. */
@Component
public class TotpEncryptionKeyMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TotpEncryptionKeyMigration.class);
    private final MongoTemplate mongo;
    private final SecretEncryptionService encryption;

    public TotpEncryptionKeyMigration(MongoTemplate mongo, SecretEncryptionService encryption) {
        this.mongo = mongo;
        this.encryption = encryption;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!encryption.hasLegacyKey()) return;
        int migrated = 0;
        int conflicts = 0;
        for (AccountSecurity document : mongo.findAll(AccountSecurity.class)) {
            Update update = new Update();
            boolean hasChange = false;
            if (document.getSecret() != null) {
                var value = encryption.reencryptLegacyValueIfNeeded(document.getSecret());
                if (value.isPresent()) { update.set("secret", value.get()); hasChange = true; }
            }
            if (document.getPendingSecret() != null) {
                var value = encryption.reencryptLegacyValueIfNeeded(document.getPendingSecret());
                if (value.isPresent()) { update.set("pendingSecret", value.get()); hasChange = true; }
            }
            if (!hasChange) continue;
            Query unchanged = Query.query(Criteria.where("_id").is(document.getId()));
            if (document.getSecret() == null) unchanged.addCriteria(Criteria.where("secret").is(null));
            else unchanged.addCriteria(Criteria.where("secret").is(document.getSecret()));
            if (document.getPendingSecret() == null) unchanged.addCriteria(Criteria.where("pendingSecret").is(null));
            else unchanged.addCriteria(Criteria.where("pendingSecret").is(document.getPendingSecret()));
            if (mongo.updateFirst(unchanged, update, AccountSecurity.class).getModifiedCount() == 1) migrated++;
            else conflicts++;
        }
        log.info("TOTP encryption-key migration complete: migrated={}, concurrentConflicts={}", migrated, conflicts);
        if (conflicts > 0) log.warn("Some TOTP records changed during key migration; restart once more after checking the migration log");
    }
}
