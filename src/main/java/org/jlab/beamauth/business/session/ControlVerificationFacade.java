package org.jlab.beamauth.business.session;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.security.DeclareRoles;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.mail.MessagingException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import org.jlab.beamauth.persistence.entity.Authorization;
import org.jlab.beamauth.persistence.entity.BeamDestination;
import org.jlab.beamauth.persistence.entity.ControlVerification;
import org.jlab.beamauth.persistence.entity.CreditedControl;
import org.jlab.beamauth.persistence.entity.DestinationAuthorization;
import org.jlab.beamauth.persistence.entity.DestinationAuthorizationPK;
import org.jlab.beamauth.persistence.entity.Staff;
import org.jlab.beamauth.persistence.entity.VerificationHistory;
import org.jlab.beamauth.persistence.entity.Workgroup;
import org.jlab.beamauth.presentation.util.BeamAuthFunctions;
import org.jlab.jlog.Body;
import org.jlab.jlog.Library;
import org.jlab.jlog.LogEntry;
import org.jlab.jlog.LogEntryAdminExtension;
import org.jlab.smoothness.business.exception.UserFriendlyException;
import org.jlab.smoothness.business.service.EmailService;
import org.jlab.smoothness.business.util.IOUtil;
import org.jlab.smoothness.business.util.TimeUtil;

/**
 *
 * @author ryans
 */
@Stateless
@DeclareRoles({"oability"})
public class ControlVerificationFacade extends AbstractFacade<ControlVerification> {

    private static final Logger LOGGER = Logger.getLogger(
            ControlVerificationFacade.class.getName());
    @PersistenceContext(unitName = "beam-authorizationPU")
    private EntityManager em;
    @EJB
    CreditedControlFacade controlFacade;
    @EJB
    BeamDestinationFacade destinationFacade;
    @EJB
    StaffFacade staffFacade;
    @EJB
    AuthorizationFacade authorizationFacade;
    @EJB
    BeamDestinationFacade beamDestinationFacade;

    @Override
    protected EntityManager getEntityManager() {
        return em;
    }

    public ControlVerificationFacade() {
        super(ControlVerification.class);
    }

    @PermitAll
    public List<ControlVerification> findByBeamDestination(BigInteger beamDestinationId) {
        TypedQuery<ControlVerification> q = em.createQuery(
                "select a from ControlVerification a join fetch a.creditedControl where a.beamDestination.beamDestinationId = :beamDestinationId order by a.creditedControl.weight asc",
                ControlVerification.class);

        q.setParameter("beamDestinationId", beamDestinationId);

        return q.getResultList();
    }

    @RolesAllowed("oability")
    public void toggle(BigInteger controlId, BigInteger destinationId) {
        ControlVerification verification = find(controlId, destinationId);

        String username = checkAuthenticated();

        Staff modifiedStaff = staffFacade.findByUsername(username);

        if (verification == null) {
            verification = new ControlVerification();
            verification.setModifiedBy(modifiedStaff);
            verification.setModifiedDate(new Date());
            CreditedControl control = controlFacade.find(controlId);
            verification.setCreditedControl(control);
            BeamDestination destination = destinationFacade.find(destinationId);
            verification.setBeamDestination(destination);
            verification.setVerificationId(100);
            create(verification);
        } else {
            remove(verification);
        }
    }

    @PermitAll
    public ControlVerification find(BigInteger controlId, BigInteger destinationId) {
        TypedQuery<ControlVerification> q = em.createQuery(
                "select a from ControlVerification a where a.creditedControl.creditedControlId = :creditedControlId and a.beamDestination.beamDestinationId = :beamDestinationId",
                ControlVerification.class);

        q.setParameter("creditedControlId", controlId);
        q.setParameter("beamDestinationId", destinationId);

        List<ControlVerification> verificationList = q.getResultList();

        ControlVerification verification = null;

        if (verificationList != null && !verificationList.isEmpty()) {
            verification = verificationList.get(0);
        }

        return verification;
    }

    @PermitAll
    public List<ControlVerification> edit(BigInteger[] controlVerificationIdArray,
            Integer verificationId, Date verificationDate, String verifiedUsername,
            Date expirationDate, String comments) throws UserFriendlyException {
        String username = checkAuthenticated();

        Staff modifiedStaff = staffFacade.findByUsername(username);

        if (modifiedStaff == null) {
            throw new UserFriendlyException("staff with username " + username + " not found");
        }

        if (verifiedUsername == null || verifiedUsername.trim().isEmpty()) {
            throw new UserFriendlyException("verified by must not be empty");
        }

        Staff verifiedStaff = staffFacade.findByUsername(verifiedUsername);

        if (verifiedStaff == null) {
            throw new UserFriendlyException("verified by with username " + verifiedUsername + " not found");
        }

        if (verificationId == null) {
            throw new UserFriendlyException("verification status must not be empty");
        }

        if (verificationDate == null) {
            throw new UserFriendlyException("verification date must not be empty");
        }

        if (controlVerificationIdArray == null) {
            throw new UserFriendlyException("control verification ID array must not be empty");
        }

        Date now = new Date();

        if (expirationDate != null && expirationDate.before(now)) {
            throw new UserFriendlyException("expiration date cannot be in the past");
        }

        List<ControlVerification> downgradeList = new ArrayList<>();

        for (BigInteger controlVerificationId : controlVerificationIdArray) {
            if (controlVerificationId == null) {
                throw new UserFriendlyException("control verification ID must not be null");
            }

            ControlVerification verification = find(controlVerificationId);

            if (verification == null) {
                throw new UserFriendlyException("control verification with ID " + controlVerificationId
                        + " not found");
            }

            checkAdminOrGroupLeader(username, verification.getCreditedControl().getGroup().getLeaderWorkgroup());

            boolean downgrade = false;

            // If verificationId is changing and it is a downgrade
            if (verification.getVerificationId() != verificationId
                    && verification.getVerificationId() < verificationId) {
                downgrade = true;
            }

            Date modifiedDate = new Date();

            verification.setModifiedBy(modifiedStaff);
            verification.setModifiedDate(modifiedDate);
            verification.setVerificationId(verificationId);
            verification.setVerificationDate(verificationDate);
            verification.setVerifiedBy(verifiedStaff);
            verification.setExpirationDate(expirationDate);
            verification.setComments(comments);

            if (downgrade) {
                downgradeList.add(verification);
            }

            VerificationHistory history = new VerificationHistory();
            history.setVerificationId(verificationId);
            history.setModifiedBy(modifiedStaff);
            history.setModifiedDate(modifiedDate);
            history.setVerificationDate(verificationDate);
            history.setVerifiedBy(verifiedStaff);
            history.setExpirationDate(expirationDate);
            history.setComments(comments);
            history.setControlVerification(verification);
            em.persist(history);
        }

        if (!downgradeList.isEmpty()) {
            clearDirectorPermissionForDowngrade(downgradeList);
        }

        return downgradeList;
    }

    @PermitAll
    public String getExpiredMessageBody(String proxyServerName,
            List<DestinationAuthorization> expiredAuthorizationList,
            List<ControlVerification> expiredVerificationList,
            List<DestinationAuthorization> upcomingAuthorizationExpirationList,
            List<ControlVerification> upcomingVerificationExpirationList) {
        StringBuilder builder = new StringBuilder();

        SimpleDateFormat formatter = new SimpleDateFormat(TimeUtil.getFriendlyDateTimePattern());

        if (expiredAuthorizationList != null && !expiredAuthorizationList.isEmpty()) {
            builder.append("<h1>--- Expired Director's Authorizations ---</h1>\n");
            for (DestinationAuthorization authorization : expiredAuthorizationList) {
                builder.append("</div>\n<div><b>Beam Destination:</b> ");
                builder.append(BeamAuthFunctions.formatDestination(authorization.getDestination()));
                builder.append("</div>\n<div><b>Expired On:</b> ");
                builder.append(formatter.format(authorization.getExpirationDate()));
                builder.append("</div>\n<div><b>Comments:</b> ");
                builder.append(IOUtil.escapeXml(
                        authorization.getComments() == null ? "" : authorization.getComments()));
                builder.append("<br/><br/>\n");
            }
        }

        if (expiredVerificationList != null && !expiredVerificationList.isEmpty()) {
            builder.append("<h1>--- Expired Credited Control Verifications ---</h1>\n");

            for (ControlVerification v : expiredVerificationList) {

                builder.append("<div><b>Credited Control:</b> ");
                builder.append(v.getCreditedControl().getName());
                builder.append("</div>\n<div><b>Beam Destination:</b> ");
                builder.append(BeamAuthFunctions.formatDestination(v.getBeamDestination()));
                builder.append("</div>\n<div><b>Verified On:</b> ");
                builder.append(formatter.format(v.getVerificationDate()));
                builder.append("</div>\n<div><b>Verified By:</b> ");
                builder.append(BeamAuthFunctions.formatStaff(v.getVerifiedBy()));
                builder.append("</div>\n<div><b>Expired On:</b> ");
                builder.append(formatter.format(v.getExpirationDate()));
                builder.append("</div>\n<div><b>Comments:</b> ");
                builder.append(IOUtil.escapeXml(v.getComments() == null ? "" : v.getComments()));
                builder.append("<br/><br/>\n");
            }

            builder.append("<br/><br/>\n");
        }

        if (upcomingAuthorizationExpirationList != null
                && !upcomingAuthorizationExpirationList.isEmpty()) {
            builder.append("<h1>--- Director's Authorizations Expiring Soon ---</h1>\n");

            for (DestinationAuthorization authorization : upcomingAuthorizationExpirationList) {

                builder.append("<div><b>Beam Destination:</b> ");
                builder.append(BeamAuthFunctions.formatDestination(authorization.getDestination()));
                builder.append("</div>\n<div><b>Expires On:</b> ");
                builder.append(formatter.format(authorization.getExpirationDate()));
                builder.append("</div>\n<div><b>Comments:</b> ");
                builder.append(IOUtil.escapeXml(
                        authorization.getComments() == null ? "" : authorization.getComments()));
                builder.append("<br/><br/>\n");
            }

            builder.append("<br/><br/>\n");
        }

        if (upcomingVerificationExpirationList != null
                && !upcomingVerificationExpirationList.isEmpty()) {
            builder.append("<h1>--- Credited Control Verifications Expiring Soon ---</h1>\n");

            for (ControlVerification v : upcomingVerificationExpirationList) {

                builder.append("<div><b>Credited Control:</b> ");
                builder.append(v.getCreditedControl().getName());
                builder.append("</div>\n<div><b>Beam Destination:</b> ");
                builder.append(BeamAuthFunctions.formatDestination(v.getBeamDestination()));
                builder.append("</div>\n<div><b>Verified On:</b> ");
                builder.append(formatter.format(v.getVerificationDate()));
                builder.append("</div>\n<div><b>Verified By:</b> ");
                builder.append(BeamAuthFunctions.formatStaff(v.getVerifiedBy()));
                builder.append("</div>\n<div><b>Expiring On:</b> ");
                builder.append(formatter.format(v.getExpirationDate()));
                builder.append("</div>\n<div><b>Comments:</b> ");
                builder.append(IOUtil.escapeXml(v.getComments() == null ? "" : v.getComments()));
                builder.append("<br/><br/>\n");
            }
        }

        builder.append("<br/><br/>\n");
        builder.append("</div><div>\n\n<b>See:</b> <a href=\"https://").append(proxyServerName).append(
                "/beam-auth/\">Beam Authorization</a></div>\n");

        return builder.toString();
    }

    @PermitAll
    public String getVerificationDowngradedMessageBody(String proxyServerName,
            List<ControlVerification> downgradeList) {
        StringBuilder builder = new StringBuilder();

        SimpleDateFormat formatter = new SimpleDateFormat(TimeUtil.getFriendlyDateTimePattern());

        ControlVerification verification = downgradeList.get(0);

        builder.append("<div><b>Credited Control:</b> ");
        builder.append(verification.getCreditedControl().getName());
        builder.append("</div>\n<div><b>Beam Destinations:</b> ");
        for (ControlVerification v : downgradeList) {
            builder.append("<div>");
            builder.append(BeamAuthFunctions.formatDestination(v.getBeamDestination()));
            builder.append("</div>");
        }
        builder.append("</div>\n<div><b>Modified On:</b> ");
        builder.append(formatter.format(verification.getVerificationDate()));
        builder.append("</div>\n<div><b>Modified By:</b> ");
        builder.append(BeamAuthFunctions.formatStaff(verification.getVerifiedBy()));
        builder.append("</div>\n<div><b>Verification:</b> ");
        builder.append(
                verification.getVerificationId() == 1 ? "Verified" : (verification.getVerificationId()
                == 50 ? "Provisionally Verified" : "Not Verified"));
        builder.append("</div>\n<div><b>Comments:</b> ");
        builder.append(IOUtil.escapeXml(verification.getComments()));
        builder.append("</div><div>\n\n<b>See:</b> <a href=\"https://").append(proxyServerName).append(
                "/beam-auth/\">Beam Authorization</a></div>\n");

        return builder.toString();
    }

    @PermitAll
    public long sendVerificationDowngradedELog(String body, String logbookServerName) throws
            UserFriendlyException {
        String username = checkAuthenticated();

        String subject = System.getenv("BA_DOWNGRADED_SUBJECT");

        String logbooks = System.getenv("BA_BOOKS_CSV");

        if (logbooks == null || logbooks.isEmpty()) {
            logbooks = "TLOG";
            LOGGER.log(Level.WARNING,
                    "Environment variable 'BA_BOOKS_CSV' not found, using default TLOG");
        }

        Properties config = Library.getConfiguration();

        config.setProperty("SUBMIT_URL", "https://" + logbookServerName + "/incoming");
        config.setProperty("FETCH_URL", "https://" + logbookServerName + "/entry");

        LogEntry entry = new LogEntry(subject, logbooks);

        entry.setBody(body, Body.ContentType.HTML);
        entry.setTags("Readme");

        LogEntryAdminExtension extension = new LogEntryAdminExtension(entry);
        extension.setAuthor(username);

        long logId;

        try {
            logId = entry.submitNow();
        } catch (Exception e) {
            throw new UserFriendlyException("Unable to send elog", e);
        }

        return logId;
    }

    @PermitAll
    public void sendVerificationDowngradedEmail(String body) throws UserFriendlyException {
            String toCsv = System.getenv("BA_DOWNGRADED_EMAIL_CSV");

            String proxyHostname = System.getenv("PROXY_HOSTNAME");

            String subject = System.getenv("BA_DOWNGRADED_SUBJECT");

            EmailService emailService = new EmailService();

            String sender = System.getenv("BA_EMAIL_SENDER");

            emailService.sendEmail(sender,sender, toCsv, subject, body, true);
    }

    @PermitAll
    public List<DestinationAuthorization> checkForAuthorizedButExpired(Authorization mostRecent) {
        TypedQuery<DestinationAuthorization> q = em.createQuery(
                "select a from DestinationAuthorization a where a.authorization.authorizationId = :authId and a.expirationDate < sysdate and a.beamMode != 'None' and a.destination.authDestination.active = true order by a.destinationAuthorizationPK.beamDestinationId asc",
                DestinationAuthorization.class);

        BigInteger authId = mostRecent.getAuthorizationId();

        q.setParameter("authId", authId);

        return q.getResultList();
    }

    @PermitAll
    public List<ControlVerification> checkForExpired() {
        TypedQuery<ControlVerification> q = em.createQuery(
                "select a from ControlVerification a join fetch a.creditedControl where a.expirationDate < sysdate and a.beamDestination.authDestination.active = true order by a.creditedControl.weight asc",
                ControlVerification.class);

        return q.getResultList();
    }

    @PermitAll
    public List<ControlVerification> checkForVerifiedButExpired() {
        TypedQuery<ControlVerification> q = em.createQuery(
                "select a from ControlVerification a join fetch a.creditedControl where a.expirationDate < sysdate and a.beamDestination.authDestination.active = true and a.verificationId in (1, 50) order by a.creditedControl.weight asc",
                ControlVerification.class);

        return q.getResultList();
    }

    @PermitAll
    public void revokeExpiredAuthorizations(List<DestinationAuthorization> authorizationList) {
        LOGGER.log(Level.FINEST, "I think I've got something authorization-wise to downgrade");
        this.clearDirectorPermissionByDestinationAuthorization(authorizationList);
    }

    @PermitAll
    public void revokeExpiredVerifications(List<ControlVerification> expiredList) {
        Query q = em.createQuery(
                "update ControlVerification a set a.verificationId = 100, a.comments = 'Expired', a.verifiedBy = null, a.verificationDate = :vDate, a.modifiedDate = :vDate, a.modifiedBy.staffId = 26 where a.controlVerificationId in :list");

        List<BigInteger> expiredIdList = new ArrayList<>();

        Date modifiedDate = new Date();

        if (expiredList != null) {
            for (ControlVerification v : expiredList) {
                expiredIdList.add(v.getControlVerificationId());
            }
        }

        q.setParameter("list", expiredIdList);
        q.setParameter("vDate", modifiedDate);

        q.executeUpdate();

        insertExpiredHistory(expiredList, modifiedDate);

        em.flush();

        clearDirectorPermissionForExpired(expiredList);
    }

    @PermitAll
    public void clearDirectorPermissionForExpired(List<ControlVerification> verificationList) {
        clearDirectorPermissionByCreditedControl(verificationList, true);
    }

    @PermitAll
    public void clearDirectorPermissionForDowngrade(List<ControlVerification> verificationList) {
        clearDirectorPermissionByCreditedControl(verificationList, false);
    }

    private void clearDirectorPermissionByCreditedControl(List<ControlVerification> verificationList,
            Boolean expiration) {
        String reason = "expiration";

        if (!expiration) {
            reason = "downgrade";
        }

        Authorization authorization = authorizationFacade.findCurrent();

        Authorization authClone = authorization.createAdminClone();
        //authClone.setDestinationAuthorizationList(new ArrayList<>());
        List<DestinationAuthorization> newList = new ArrayList<>();

        boolean atLeastOne = false;

        // The destination authorization list will be null if already cleared previously: remember there are two ways in which a clear can happen and they can race to see who clears permissions first:
        // (1) director's expiration vs (2) credited control expiration
        if (authorization.getDestinationAuthorizationList() != null) {
            for (DestinationAuthorization auth : authorization.getDestinationAuthorizationList()) {
                DestinationAuthorization destClone = auth.createAdminClone(authClone);
                //authClone.getDestinationAuthorizationList().add(destClone);
                newList.add(destClone);

                if ("None".equals(auth.getBeamMode())) {
                    continue; // Already None so no need to revoke; move on to next
                }
                BigInteger destinationId = auth.getDestinationAuthorizationPK().getBeamDestinationId();
                for (ControlVerification verification : verificationList) {
                    if (destinationId.equals(verification.getBeamDestination().getBeamDestinationId())) {
                        destClone.setBeamMode("None");
                        destClone.setCwLimit(null);
                        destClone.setComments(
                                "Permission automatically revoked due to group credited control verification "
                                + reason);
                        LOGGER.log(Level.FINEST, "Found something to downgrade");
                        atLeastOne = true;
                        break; // Found a match so revoke and then break out of loop
                    }
                }
            }
        }

        if (atLeastOne) {
            em.persist(authClone);
            for (DestinationAuthorization da : newList) {
                DestinationAuthorizationPK pk = new DestinationAuthorizationPK();
                pk.setBeamDestinationId(da.getDestination().getBeamDestinationId());
                pk.setAuthorizationId(authClone.getAuthorizationId());
                da.setDestinationAuthorizationPK(pk);
                em.persist(da);
            }
        }
    }

    private void clearDirectorPermissionByDestinationAuthorization(
            List<DestinationAuthorization> destinationList) {
        Authorization authorization = authorizationFacade.findCurrent();

        Authorization authClone = authorization.createAdminClone();
        //authClone.setDestinationAuthorizationList(new ArrayList<>());
        List<DestinationAuthorization> newList = new ArrayList<>();

        boolean atLeastOne = false;

        // The destination authorization list will be null if already cleared previously: remember there are two ways in which a clear can happen and they can race to see who clears permissions first:
        // (1) director's expiration vs (2) credited control expiration
        if (authorization.getDestinationAuthorizationList() != null) {
            for (DestinationAuthorization auth : authorization.getDestinationAuthorizationList()) {
                DestinationAuthorization destClone = auth.createAdminClone(authClone);
                //authClone.getDestinationAuthorizationList().add(destClone);
                newList.add(destClone);

                if ("None".equals(auth.getBeamMode())) {
                    continue; // Already None so no need to revoke; move on to next
                }
                if (destinationList.contains(auth)) {
                    destClone.setBeamMode("None");
                    destClone.setCwLimit(null);
                    destClone.setComments(
                            "Permission automatically revoked due to director's authorization expiration");
                    LOGGER.log(Level.FINEST, "Found something to downgrade");
                    atLeastOne = true;
                }
            }
        }

        if (atLeastOne) {
            em.persist(authClone);
            for (DestinationAuthorization da : newList) {
                DestinationAuthorizationPK pk = new DestinationAuthorizationPK();
                pk.setBeamDestinationId(da.getDestination().getBeamDestinationId());
                pk.setAuthorizationId(authClone.getAuthorizationId());
                da.setDestinationAuthorizationPK(pk);
                em.persist(da);
            }
        }
    }

    @PermitAll
    public void insertExpiredHistory(List<ControlVerification> verificationList, Date modifiedDate) {
        for (ControlVerification v : verificationList) {
            VerificationHistory history = new VerificationHistory();
            Staff admin = new Staff();
            admin.setStaffId(BigInteger.valueOf(26L));
            history.setModifiedBy(admin);
            history.setModifiedDate(modifiedDate);
            history.setVerificationId(100);
            history.setVerificationDate(modifiedDate);
            history.setVerifiedBy(null);
            history.setExpirationDate(v.getExpirationDate());
            history.setComments("Expired");
            history.setControlVerification(v);
            em.persist(history);
        }
    }

    @PermitAll
    public List<ControlVerification> checkForUpcomingVerificationExpirations() {
        TypedQuery<ControlVerification> q = em.createQuery(
                "select a from ControlVerification a join fetch a.creditedControl where a.expirationDate >= sysdate and (a.expirationDate - 7) <= sysdate and a.verificationId in (1, 50) and a.beamDestination.authDestination.active = true order by a.creditedControl.weight asc",
                ControlVerification.class);

        return q.getResultList();
    }

    private List<DestinationAuthorization> checkForUpcomingAuthorizationExpirations(
            Authorization auth) {
        List<DestinationAuthorization> upcomingExpirations = new ArrayList<>();

        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, 3);
        Date threeDaysFromNow = cal.getTime();

        if (auth.getDestinationAuthorizationList() != null) {
            for (DestinationAuthorization dest : auth.getDestinationAuthorizationList()) {
                if (!"None".equals(dest.getBeamMode()) && dest.getExpirationDate().after(now)
                        && dest.getExpirationDate().before(threeDaysFromNow)) {
                    upcomingExpirations.add(dest);
                }
            }
        }

        return upcomingExpirations;
    }

    @PermitAll
    public void notifyAdmins(List<DestinationAuthorization> expiredAuthorizationList,
            List<ControlVerification> expiredVerificationList,
            List<DestinationAuthorization> upcomingAuthorizationExpirationList,
            List<ControlVerification> upcomingVerificationExpirationList,
            String proxyServerName) throws MessagingException, UserFriendlyException {
        String toCsv = System.getenv("BA_UPCOMING_EXPIRATION_EMAIL_CSV");

        String subject = System.getenv("BA_UPCOMING_EXPIRATION_SUBJECT");

        String body = getExpiredMessageBody(proxyServerName, expiredAuthorizationList,
                expiredVerificationList,
                upcomingAuthorizationExpirationList,
                upcomingVerificationExpirationList);

        EmailService emailService = new EmailService();

        String sender = System.getenv("BA_EMAIL_SENDER");

        emailService.sendEmail(sender, sender, toCsv, subject, body, true);
    }

    @PermitAll
    public void notifyOps(List<DestinationAuthorization> expiredAuthorizationList,
            List<ControlVerification> expiredVerificationList,
            String proxyServerName) throws MessagingException, UserFriendlyException {
        String toCsv = System.getenv("BA_EXPIRED_EMAIL_CSV");

        String subject = System.getenv("BA_EXPIRED_SUBJECT");

        String body = getExpiredMessageBody(proxyServerName, expiredAuthorizationList,
                expiredVerificationList, null, null);

        EmailService emailService = new EmailService();

        String sender = System.getenv("BA_EMAIL_SENDER");

        emailService.sendEmail(sender, sender, toCsv, subject, body, true);
        LOGGER.log(Level.FINEST, "notifyOps, toCsv: {0], body: {1}", new Object[]{toCsv, body});
    }

    @PermitAll
    public void notifyGroups(List<ControlVerification> expiredList,
            List<ControlVerification> upcomingExpirationsList,
            String proxyServerName) throws MessagingException, UserFriendlyException {
        Map<Workgroup, List<ControlVerification>> expiredGroupMap = new HashMap<>();
        Map<Workgroup, List<ControlVerification>> upcomingExpirationGroupMap = new HashMap<>();

        String subject = System.getenv("BA_UPCOMING_EXPIRATION_SUBJECT");

        LOGGER.log(Level.FINEST, "Expirations:");
        if (expiredList != null) {
            for (ControlVerification c : expiredList) {
                LOGGER.log(Level.FINEST, c.toString());
                Workgroup workgroup = c.getCreditedControl().getGroup().getLeaderWorkgroup();
                List<ControlVerification> groupList = expiredGroupMap.get(workgroup);
                if (groupList == null) {
                    groupList = new ArrayList<>();
                    expiredGroupMap.put(workgroup, groupList);
                }
                groupList.add(c);
            }
        } else {
            LOGGER.log(Level.FINEST, "No expirations");
        }

        LOGGER.log(Level.FINEST, "Upcoming Expirations:");
        if (upcomingExpirationsList != null) {
            for (ControlVerification c : upcomingExpirationsList) {
                LOGGER.log(Level.FINEST, c.toString());
                Workgroup workgroup = c.getCreditedControl().getGroup().getLeaderWorkgroup();
                List<ControlVerification> groupList = upcomingExpirationGroupMap.get(workgroup);
                if (groupList == null) {
                    groupList = new ArrayList<>();
                    upcomingExpirationGroupMap.put(workgroup, groupList);
                }
                groupList.add(c);
            }
        } else {
            LOGGER.log(Level.FINEST, "No upcoming expirations");
        }

        Set<Workgroup> allGroups = new HashSet<>(expiredGroupMap.keySet());
        allGroups.addAll(upcomingExpirationGroupMap.keySet());

        for (Workgroup w : allGroups) {

            List<String> toAddresses = new ArrayList<>();

            if (w.getGroupLeaderList() != null) {
                for (Staff s : w.getGroupLeaderList()) {
                    if (s.getUsername() != null) {
                        toAddresses.add((s.getUsername() + "@jlab.org"));
                    }
                }
            }

            List<ControlVerification> groupExpiredList = expiredGroupMap.get(w);
            List<ControlVerification> groupUpcomingExpirationsList = upcomingExpirationGroupMap.get(
                    w);

            String sender = System.getenv("BA_EMAIL_SENDER");

            String body = getExpiredMessageBody(proxyServerName, null, groupExpiredList,
                    null, groupUpcomingExpirationsList);

            if (!toAddresses.isEmpty()) {
                EmailService emailService = new EmailService();

                String toCsv = toAddresses.get(0);

                for(int i = 1; i < toAddresses.size(); i++) {
                    toCsv = toCsv + "," + toAddresses.get(i);
                }

                if("accweb.acc.jlab.org".equals(proxyServerName)) {
                    emailService.sendEmail(sender, sender, toCsv, subject, body, true);
                } else {
                    LOGGER.log(Level.FINEST, "notifyGroups, toCsv: {0}, body: {1}", new Object[]{toCsv, body});
                }
            }
        }
    }

    @PermitAll
    public void notifyUsersOfExpirationsAndUpcomingExpirations(
            List<DestinationAuthorization> expiredAuthorizationList,
            List<ControlVerification> expiredVerificationList,
            List<DestinationAuthorization> upcomingAuthorizationExpirationList,
            List<ControlVerification> upcomingVerificationExpirationList) {

        boolean expiredAuth = (expiredAuthorizationList != null
                && !expiredAuthorizationList.isEmpty());
        boolean expiredVer = (expiredVerificationList != null && !expiredVerificationList.isEmpty());
        boolean upcomingAuth = (upcomingAuthorizationExpirationList != null
                && !upcomingAuthorizationExpirationList.isEmpty());
        boolean upcomingVer = (upcomingVerificationExpirationList != null
                && !upcomingVerificationExpirationList.isEmpty());

        if (expiredAuth || expiredVer || upcomingAuth || upcomingVer) {

            LOGGER.log(Level.FINEST, "Notifying users");
            String proxyServerName = System.getenv("PROXY_HOSTNAME");



            try {
                // Admins
                notifyAdmins(expiredAuthorizationList, expiredVerificationList,
                        upcomingAuthorizationExpirationList,
                        upcomingVerificationExpirationList, proxyServerName);

                    // Ops
                    if (expiredAuth || expiredVer) {
                        notifyOps(expiredAuthorizationList, expiredVerificationList,
                                proxyServerName);
                    }

                    // Groups
                    if (expiredVer || upcomingVer) {
                        notifyGroups(expiredVerificationList, upcomingVerificationExpirationList,
                                proxyServerName);
                    }

            } catch (MessagingException | NullPointerException | UserFriendlyException e) {
                LOGGER.log(Level.WARNING, "Unable to send email", e);
            }
        } else {
            LOGGER.log(Level.FINEST, "Nothing to notify users about");
        }
    }

    @PermitAll
    public void performExpirationCheck(boolean checkForUpcoming) {
        LOGGER.log(Level.FINEST, "Expiration Check: Director's authorizations...");
        Authorization auth = authorizationFacade.findCurrent();
        List<DestinationAuthorization> expiredAuthorizationList = checkForAuthorizedButExpired(auth);
        if (expiredAuthorizationList != null && !expiredAuthorizationList.isEmpty()) {
            LOGGER.log(Level.FINEST, "Expiration Check: Revoking expired authorization");
            revokeExpiredAuthorizations(expiredAuthorizationList);
        }

        LOGGER.log(Level.FINEST, "Expiration Check: Checking for expired verifications...");
        List<ControlVerification> expiredVerificationList = checkForVerifiedButExpired(); // only items which are "verified" or "provisionally verified", but need to be "not verified" due to expiration
        if (expiredVerificationList != null && !expiredVerificationList.isEmpty()) {
            LOGGER.log(Level.FINEST, "Expiration Check: Revoking expired verifications...");
            revokeExpiredVerifications(expiredVerificationList);
        }

        List<ControlVerification> upcomingVerificationExpirationList = null;
        List<DestinationAuthorization> upcomingAuthorizationExpirationList = null;
        if (checkForUpcoming) {
            LOGGER.log(Level.FINEST,
                    "Expiration Check: Checking for upcoming verification expirations...");
            upcomingVerificationExpirationList = checkForUpcomingVerificationExpirations();

            LOGGER.log(Level.FINEST,
                    "Expiration Check: Checking for upcoming authorization expirations...");
            upcomingAuthorizationExpirationList = checkForUpcomingAuthorizationExpirations(auth);
        }

        notifyUsersOfExpirationsAndUpcomingExpirations(expiredAuthorizationList,
                expiredVerificationList, upcomingAuthorizationExpirationList,
                upcomingVerificationExpirationList);
    }

    @PermitAll
    public ControlVerification
            findWithCreditedControl(BigInteger controlVerificationId) {
        TypedQuery<ControlVerification> q = em.createQuery(
                "select a from ControlVerification a join fetch a.creditedControl where a.controlVerificationId = :id",
                ControlVerification.class
        );

        q.setParameter("id", controlVerificationId);

        List<ControlVerification> resultList = q.getResultList();

        ControlVerification verification = null;

        if (resultList != null && !resultList.isEmpty()) {
            verification = resultList.get(0);
        }

        return verification;
    }
}
