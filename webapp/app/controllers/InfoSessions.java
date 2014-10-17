package controllers;

import be.ugent.degage.db.DataAccessContext;
import be.ugent.degage.db.Filter;
import be.ugent.degage.db.FilterField;
import be.ugent.degage.db.dao.*;
import be.ugent.degage.db.models.*;
import controllers.util.Addresses;
import controllers.util.FormHelper;
import controllers.util.Pagination;
import db.CurrentUser;
import db.DataAccess;
import db.InjectContext;
import notifiers.Notifier;
import org.joda.time.DateTime;
import org.joda.time.MutableDateTime;
import play.data.DynamicForm;
import play.data.Form;
import play.libs.F;
import play.mvc.Controller;
import play.mvc.Result;
import play.twirl.api.Html;
import providers.DataProvider;
import views.html.infosession.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static controllers.util.Addresses.getCountryList;
import static controllers.util.Addresses.modifyAddress;

/**
 * Created by Cedric on 2/21/14.
 */
public class InfoSessions extends Controller {

    private static List<String> typeList = null;

    private static List<String> getTypeList() {
        if (typeList == null) {
            typeList = new ArrayList<>();
            InfoSessionType[] types = InfoSessionType.values();
            for (InfoSessionType t : types) {
                typeList.add(t.getDescription());
            }
        }
        return typeList;
    }

    public static class InfoSessionCreationModel {
        public Integer userId;
        public DateTime time;
        public Integer max_enrollees;
        public String type;
        public String type_alternative;
        public String comments;
        public Addresses.EditAddressModel address = new Addresses.EditAddressModel();

        public Addresses.EditAddressModel getAddress() {
            return address;
        }

        public void setAddress(Addresses.EditAddressModel address) {
            this.address = address;
        }

        public static int getInt(Integer i) {
            return i == null ? 0 : i;
        }

        public String validate() {
            String error = "";
            if (userId == null || userId == 0) {
                error += "Gelieve een host te selecteren. ";
            }
            if (time == null) {
                error += "Gelieve het tijdsveld in te vullen. ";
            }
            if (InfoSessionType.getTypeFromString(type) == InfoSessionType.OTHER && (type_alternative == null || type_alternative.equals(""))) {
                error += "Gelieve een alternatief type in te geven of een ander type te selecteren. ";
            }
            if ("".equals(error)) return null;
            else return error;
        }

        public void populate(InfoSession i) {
            if (i == null) return;

            userId = i.getHost().getId();
            time = i.getTime();
            max_enrollees = i.getMaxEnrollees();
            type = i.getType().getDescription();
            type_alternative = i.getTypeAlternative();
            comments = i.getComments();
            address.populate(i.getAddress());
        }

    }

    /**
     * Method: GET
     *
     * @return An infosession form
     */
    @AllowRoles(value = {UserRole.INFOSESSION_ADMIN})
    @InjectContext
    public static Result newSession() {
        User user = DataProvider.getUserProvider().getUser();
        Form<InfoSessionCreationModel> editForm = Form.form(InfoSessionCreationModel.class);

        InfoSessionCreationModel model = new InfoSessionCreationModel();
        model.userId = user.getId();
        model.address.populate(user.getAddressDomicile());
        model.type = InfoSessionType.NORMAL.getDescription();
        editForm = editForm.fill(model);
        return ok(addinfosession.render(editForm, 0, getCountryList(), getTypeList()));
    }

    /**
     * Method: GET
     *
     * @param sessionId SessionId to edit
     * @return An infosession form for given id
     */
    @AllowRoles(value = {UserRole.INFOSESSION_ADMIN})
    @InjectContext
    public static Result editSession(int sessionId) {
        InfoSessionDAO dao = DataAccess.getInjectedContext().getInfoSessionDAO();
        InfoSession is = dao.getInfoSession(sessionId);
        if (is == null) {
            flash("danger", "Infosessie met ID=" + sessionId + " bestaat niet.");
            return redirect(routes.InfoSessions.showUpcomingSessions());
        } else {
            InfoSessionCreationModel model = new InfoSessionCreationModel();
            model.populate(is);

            Form<InfoSessionCreationModel> editForm = Form.form(InfoSessionCreationModel.class).fill(model);
            return ok(addinfosession.render(editForm, sessionId, getCountryList(), getTypeList()));
        }
    }

    /**
     * Method: GET
     *
     * @param sessionId SessionID to remove
     * @return A result redirect whether delete was successfull or not.
     */
    @AllowRoles(value = {UserRole.INFOSESSION_ADMIN})
    @InjectContext
    public static Result removeSession(int sessionId) {
        DataAccessContext context = DataAccess.getInjectedContext();

        context.getInfoSessionDAO().deleteInfoSession(sessionId);

        // Delete the reminder
        context.getJobDAO().deleteJob(JobType.IS_REMINDER, sessionId);

        flash("success", "De infosessie werd succesvol verwijderd.");
        return redirect(routes.InfoSessions.showUpcomingSessions());
    }

    /**
     * Method: POST
     * Edits the session for given ID, based on submitted form data
     *
     * @param sessionId SessionID to edit
     * @return Redirect to edited session, or the form if errors occured
     */
    @AllowRoles(value = {UserRole.INFOSESSION_ADMIN})
    @InjectContext
    public static Result editSessionPost(int sessionId) {
        Form<InfoSessionCreationModel> editForm = Form.form(InfoSessionCreationModel.class).bindFromRequest();
        if (editForm.hasErrors()) {
            return badRequest(addinfosession.render(editForm, sessionId, getCountryList(), getTypeList()));
        } else {
            DataAccessContext context = DataAccess.getInjectedContext();
            InfoSessionDAO dao = context.getInfoSessionDAO();
            InfoSession session = dao.getInfoSession(sessionId);
            if (session == null) {
                flash("danger", "Infosessie met ID=" + sessionId + " bestaat niet.");
                return redirect(routes.InfoSessions.showUpcomingSessions());
            }

            // Check the host field
            UserDAO udao = context.getUserDAO();
            User host = udao.getUserPartial(editForm.get().userId);

            if (host == null) {
                editForm.reject("Infosessie gastheer bestaat niet.");
                return badRequest(addinfosession.render(editForm, sessionId, getCountryList(), getTypeList()));
            }
            session.setHost(host);

            // update address
            AddressDAO adao = context.getAddressDAO();
            Address newAddress = modifyAddress(editForm.get().address, session.getAddress(), adao);
            session.setAddress(newAddress);

            // update time
            DateTime time = editForm.get().time.withSecondOfMinute(0);
            if (!session.getTime().equals(time)) {
                session.setTime(time);

                // Schedule the reminder
                JobDAO jdao = context.getJobDAO();
                jdao.deleteJob(JobType.IS_REMINDER, session.getId()); // remove old reminder

                int minutesBefore = Integer.parseInt(context.getSettingDAO().getSettingForNow("infosession_reminder"));
                MutableDateTime reminderDate = new MutableDateTime(session.getTime());
                reminderDate.addMinutes(-minutesBefore);
                jdao.createJob(JobType.IS_REMINDER, session.getId(), reminderDate.toDateTime());
            }

            // check if amountOfAttendees < new max
            int amountOfAttendees = dao.getAmountOfAttendees(session.getId());
            if (editForm.get().max_enrollees != 0 && editForm.get().max_enrollees < amountOfAttendees) {
                flash("danger", "Er zijn al meer inschrijvingen dan het nieuwe toegelaten aantal. Aantal huidige inschrijvingen: " + amountOfAttendees + ".");
                return badRequest(addinfosession.render(editForm, sessionId, getCountryList(), getTypeList()));
            } else {
                session.setMaxEnrollees(editForm.get().max_enrollees);
            }

            // type
            InfoSessionType type = InfoSessionType.getTypeFromString(editForm.get().type);
            session.setType(type);
            String typeAlternative = null;
            if (type.equals(InfoSessionType.OTHER)) {
                typeAlternative = editForm.get().type_alternative;
            }
            session.setTypeAlternative(typeAlternative);

            // comments
            session.setComments(editForm.get().comments);

            dao.updateInfoSession(session);
            flash("success", "Jouw wijzigingen werden succesvol toegepast.");
            return redirect(routes.InfoSessions.detail(sessionId));
        }
    }

    /**
     * Method: GET
     * Unenrolls the user for his subscribed infosession.
     *
     * @return A redirect to the overview page with message if unenrollment was successfull.
     */
    @AllowRoles
    @InjectContext
    public static Result unenrollSession() {
        User user = DataProvider.getUserProvider().getUser();
        InfoSessionDAO dao = DataAccess.getInjectedContext().getInfoSessionDAO();

        InfoSession alreadyAttending = dao.getAttendingInfoSession(user);
        if (alreadyAttending == null) {
            flash("danger", "Je bent niet ingeschreven voor een toekomstige infosessie.");
            return redirect(routes.InfoSessions.showUpcomingSessions());
        } else {
            dao.unregisterUser(alreadyAttending.getId(), user.getId());

            flash("success", "Je bent succesvol uitgeschreven uit deze infosessie.");
            return redirect(routes.InfoSessions.showUpcomingSessions());
        }
    }

    // TODO: reintegrate the Map (using a promise instead of a result?)
    @AllowRoles
    @InjectContext
    public static Result detail(int sessionId) {
        User user = DataProvider.getUserProvider().getUser();
        InfoSessionDAO dao = DataAccess.getInjectedContext().getInfoSessionDAO();
        InfoSession session = dao.getInfoSession(sessionId);

        if (session == null) {
            return badRequest("Sessie id bestaat niet.");
        } else {
            return ok(detail.render(
                    session,
                    dao.getAttendingInfoSession(user),
                    dao.getEnrollees(sessionId), null));
        }
    }


    /*
    @RoleSecured.RoleAuthenticated()
    @InjectContext
    public static F.Promise<Result> detail(int sessionId) {
        final User user = DataProvider.getUserProvider().getUser();
        final InfoSessionDAO dao = DataAccess.getInjectedContext().getInfoSessionDAO();
        final InfoSession session = dao.getInfoSession(sessionId);
        final Iterable<Enrollee> enrollees = dao.getEnrollees(sessionId);

        if (session == null) {
            return F.Promise.promise(new F.Function0<Result>() {
                @Override
                public Result apply() throws Throwable {
                    return badRequest("Sessie id bestaat niet.");
                }
            });
        } else {
            final InfoSession enrolled = dao.getAttendingInfoSession(user);
            if (DataProvider.getSettingProvider().getBooleanOrDefault("show_maps", true)) {
                return Maps.getLatLongPromise(session.getAddress().getId()).map(
                        new F.Function<F.Tuple<Double, Double>, Result>() {
                            public Result apply(F.Tuple<Double, Double> coordinates) {
                                return ok(detail.render(session, enrolled, enrollees,
                                        coordinates == null ? null : new Maps.MapDetails(coordinates._1, coordinates._2, 14, "Afspraak op " + session.getTime().toString("dd-MM-yyyy") + " om " + session.getTime().toString("HH:mm"))));
                            }
                        }
                );
            } else {
                return F.Promise.promise(new F.Function0<Result>() {
                    @Override
                    public Result apply() throws Throwable {
                        return ok(detail.render(session, enrolled, null));
                    }
                });
            }
        }
    }
    */

    /**
     * Method: GET
     *
     * @param sessionId Id of the session the user has to be removed from
     * @param userId    Userid of the user to be removed
     * @return Status of the operation page
     */
    @AllowRoles(value = {UserRole.INFOSESSION_ADMIN})
    @InjectContext
    public static Result removeUserFromSession(int sessionId, int userId) {

        DataAccess.getInjectedContext().getInfoSessionDAO().unregisterUser(sessionId, userId);

        flash("success", "De gebruiker werd succesvol uitgeschreven uit de infosessie.");
        return redirect(routes.InfoSessions.detail(sessionId));
    }

    /**
     * Method: GET
     *
     * @param sessionId SessionID to change userstatus on
     * @param userId    UserID of the user to change status of
     * @param status    New status of the user.
     * @return Redirect to the session detail page if successful.
     */
    @AllowRoles({UserRole.INFOSESSION_ADMIN})
    @InjectContext
    public static Result setUserSessionStatus(int sessionId, int userId, String status) {
        DataAccess.getInjectedContext().getInfoSessionDAO().setUserEnrollmentStatus(
                sessionId,
                userId,
                Enum.valueOf(EnrollementStatus.class, status)
        );
        flash("success", "De gebruikersstatus werd met succes aangepast.");
        return redirect(routes.InfoSessions.detail(sessionId));
    }

    /**
     * Method: POST
     * Adds a user to the given infosession
     *
     * @param sessionId
     * @return
     */
    @AllowRoles({UserRole.INFOSESSION_ADMIN})
    @InjectContext
    public static Result addUserToSession(int sessionId) {
        int userId;
        try {
            userId = Integer.parseInt(Form.form().bindFromRequest().get("userid"));
        } catch (Exception ex) {
            flash("danger", "Gebruiker bestaat niet.");
            return redirect(routes.InfoSessions.detail(sessionId));
        }
        DataAccessContext context = DataAccess.getInjectedContext();
        UserDAO udao = context.getUserDAO();
        InfoSessionDAO idao = context.getInfoSessionDAO();
        User user = udao.getUserPartial(userId);
        if (user == null) {
            flash("danger", "GebruikersID bestaat niet.");
            return redirect(routes.InfoSessions.detail(sessionId));
        } else {
            // TODO: simplify the user equals only by id
            for (Enrollee others : idao.getEnrollees(sessionId)) {
                if (others.getUser().getId() == user.getId()) {
                    flash("danger", "De gebruiker is reeds ingeschreven voor deze sessie.");
                    return redirect(routes.InfoSessions.detail(sessionId));
                }
            }

            // Now we enroll
            idao.registerUser(sessionId, userId); // TODO: do not allow registration if already registered

            flash("success", "De gebruiker werd succesvol toegevoegd aan deze infosessie.");
            return redirect(routes.InfoSessions.detail(sessionId));
        }
    }

    /**
     * Method: GET
     *
     * @param sessionId The sessionId to enroll to
     * @return A redirect to the detail page to which the user has subscribed
     */
    @AllowRoles
    @InjectContext
    public static Result enrollSession(int sessionId) {
        User user = DataProvider.getUserProvider().getUser();
        if (user.hasFullStatus()) {
            flash("warning", "Je bent al goedgekeurd door onze administrator. Inschrijven is wel nog steeds mogelijk.");
        }
        DataAccessContext context = DataAccess.getInjectedContext();
        InfoSessionDAO dao = context.getInfoSessionDAO();

        InfoSession alreadyAttending = dao.getAttendingInfoSession(user);
        InfoSession session = dao.getInfoSession(sessionId); //TODO: just add going subclause (like in getAttending requirement)
        int numberOfAttendees = dao.getAmountOfAttendees(sessionId);
        if (session == null) {
            flash("danger", "Sessie met ID = " + sessionId + " bestaat niet.");
            return redirect(routes.InfoSessions.showUpcomingSessions());
        } else {
            if (session.getMaxEnrollees() != 0 && numberOfAttendees >= session.getMaxEnrollees()) {
                flash("danger", "Deze infosessie zit reeds vol.");
                return redirect(routes.InfoSessions.showUpcomingSessions());
            } else {
                if (alreadyAttending != null && alreadyAttending.getTime().isAfter(DateTime.now())) {
                    dao.unregisterUser(alreadyAttending.getId(), user.getId());
                }
                dao.registerUser(sessionId, user.getId()); // TODO: disallow registration when full

                flash("success", alreadyAttending == null ? ("Je bent met succes ingeschreven voor de infosessie op " + session.getTime().toString("dd-MM-yyyy") + ".") :
                        "Je bent van infosessie veranderd naar " + session.getTime().toString("dd-MM-yyyy") + ".");
                Notifier.sendInfoSessionEnrolledMail(context, user, session);
                return redirect(routes.InfoSessions.detail(sessionId));
            }
        }
    }


    /**
     * Method: POST
     * Creates a new infosession based on submitted form data
     *
     * @return A redirect to the newly created infosession, or the infosession edit page if the form contains errors.
     */
    @AllowRoles(value = {UserRole.INFOSESSION_ADMIN})
    @InjectContext
    public static Result createNewSession() {
        Form<InfoSessionCreationModel> createForm = Form.form(InfoSessionCreationModel.class).bindFromRequest();
        if (createForm.hasErrors()) {
            return badRequest(addinfosession.render(createForm, 0, getCountryList(), getTypeList()));
        } else {
            DataAccessContext context = DataAccess.getInjectedContext();
            InfoSessionDAO dao = context.getInfoSessionDAO();

            AddressDAO adao = context.getAddressDAO();
            Address address = modifyAddress(createForm.get().address, null, adao);

            InfoSessionType type = InfoSessionType.getTypeFromString(createForm.get().type);
            String typeAlternative = null;
            if (type.equals(InfoSessionType.OTHER)) {
                typeAlternative = createForm.get().type_alternative;
            }

            UserDAO udao = context.getUserDAO();
            User host = udao.getUserPartial(createForm.get().userId);
            if (host == null) {
                createForm.reject("De gastheer ID bestaat niet.");
                return badRequest(addinfosession.render(createForm, 0, getCountryList(), getTypeList()));
            }

            InfoSession session = dao.createInfoSession(type, typeAlternative, host, address, createForm.get().time.withSecondOfMinute(0), FormHelper.toInt(createForm.get().max_enrollees), createForm.get().comments); //TODO: allow other hosts

            // Schedule the reminder
            JobDAO jdao = context.getJobDAO();
            int minutesBefore = Integer.parseInt(context.getSettingDAO().getSettingForNow("infosession_reminder"));
            MutableDateTime reminderDate = new MutableDateTime(session.getTime());
            reminderDate.addMinutes(-minutesBefore);
            jdao.createJob(JobType.IS_REMINDER, session.getId(), reminderDate.toDateTime());


            return redirect(
                    routes.InfoSessions.showUpcomingSessions() // return to infosession list
            );
        }
    }

    public static class RequestApprovalModel {
        public String message;
        public boolean acceptsTerms;

        public String validate() {
            if (!acceptsTerms)
                return "Gelieve de algemene voorwaarden te accepteren";
            else
                return null;
        }
    }

    private static List<String> checkApprovalConditions(User user, DataAccessContext context) {
        UserDAO udao = context.getUserDAO();
        FileDAO fdao = context.getFileDAO();
        user = udao.getUser(user.getId()); // gets the full user instead of small cached one
        Iterable<File> identityFiles = fdao.getIdFiles(user.getId());
        Iterable<File> licenseFiles = fdao.getLicenseFiles(user.getId());

        List<String> errors = new ArrayList<>();
        if (user.getAddressDomicile() == null)
            errors.add("Domicilieadres ontbreekt.");
        if (user.getAddressResidence() == null)
            errors.add("Verblijfsadres ontbreekt.");
        if (user.getIdentityCard() == null)
            errors.add("Identiteitskaart ontbreekt.");
        if (user.getIdentityCard() != null && (!identityFiles.iterator().hasNext()))
            errors.add("Bewijsgegevens identiteitskaart ontbreken");
        if (user.getLicense() == null)
            errors.add("Rijbewijs ontbreekt.");
        if (!user.isPayedDeposit())
            errors.add("Waarborg nog niet betaald.");
        if (user.getLicense() != null && (!licenseFiles.iterator().hasNext()))
            if (user.getCellphone() == null && user.getPhone() == null)
                errors.add("Telefoon/GSM ontbreekt.");
        return errors;
    }

    private static String getTermsAndConditions(DataAccessContext context) {
        TemplateDAO dao = context.getTemplateDAO();
        EmailTemplate t = dao.getTemplate(MailType.TERMS);
        return t.getBody();
    }

    /**
     * Method: GET
     * A page to request full user approval
     *
     * @return The page to request approval
     */
    @AllowRoles
    @InjectContext
    public static Result requestApproval() {
        User user = DataProvider.getUserProvider().getUser();
        if (user.hasFullStatus()) {
            flash("warning", "Je bent reeds een volwaardige gebruiker.");
            return redirect(routes.Dashboard.index());
        } else {
            DataAccessContext context = DataAccess.getInjectedContext();
            ApprovalDAO dao = context.getApprovalDAO();
            List<Approval> approvals = dao.getPendingApprovals(user);
            if (!approvals.isEmpty()) {
                flash("warning", "Er is reeds een toelatingsprocedure voor deze gebruiker in aanvraag.");
                return redirect(routes.Dashboard.index());
            } else {
                InfoSessionDAO idao = context.getInfoSessionDAO();
                Tuple<InfoSession, EnrollementStatus> lastSession = idao.getLastInfoSession(user);

                if (lastSession == null || lastSession.getSecond() != EnrollementStatus.PRESENT) {
                    flash("danger", "Je bent nog niet aanwezig geweest op een infosessie.");
                    return redirect(routes.InfoSessions.showUpcomingSessions());
                } else {
                    List<String> errors = checkApprovalConditions(user, context);
                    return ok(approvalrequest.render(user, errors.isEmpty() ? null : errors, Form.form(RequestApprovalModel.class), getTermsAndConditions(context), didUserGoToInfoSession()));
                }
            }
        }
    }

    // used in injected context
    // TODO: used in Dashboard only
    public static boolean approvalRequestSent() {
        User user = DataProvider.getUserProvider().getUser();
        ApprovalDAO dao = DataAccess.getInjectedContext().getApprovalDAO();
        List<Approval> approvals = dao.getPendingApprovals(user);
        return !approvals.isEmpty();
    }

    @AllowRoles
    @InjectContext
    public static Result requestApprovalPost() {
        if (CurrentUser.hasRole(UserRole.CAR_OWNER) && CurrentUser.hasRole(UserRole.CAR_USER)) {
            flash("warning", "Je bent reeds een volwaardige gebruiker.");
            return redirect(routes.Dashboard.index());
        } else {
            DataAccessContext context = DataAccess.getInjectedContext();
            Form<RequestApprovalModel> form = Form.form(RequestApprovalModel.class).bindFromRequest();
            if (form.hasErrors()) {
                User currentUser = context.getUserDAO().getUserPartial(CurrentUser.getId());
                ApprovalDAO dao = context.getApprovalDAO();
                List<Approval> approvals = dao.getPendingApprovals(currentUser);
                if (!approvals.isEmpty()) {
                    flash("warning", "Er is reeds een toelatingsprocedure voor deze gebruiker in aanvraag.");
                    return redirect(routes.Dashboard.index());
                } else {
                    InfoSessionDAO idao = context.getInfoSessionDAO();
                    Tuple<InfoSession, EnrollementStatus> lastSession = idao.getLastInfoSession(currentUser);
                    if (lastSession == null || lastSession.getSecond() != EnrollementStatus.PRESENT) {
                        flash("danger", "Je bent nog niet aanwezig geweest op een infosessie.");
                        return redirect(routes.InfoSessions.showUpcomingSessions());
                    } else {
                        List<String> errors = checkApprovalConditions(currentUser, context);
                        return badRequest(approvalrequest.render(currentUser, errors.isEmpty() ? null : errors, form, getTermsAndConditions(context), didUserGoToInfoSession()));
                    }
                }
            } else {
                ApprovalDAO dao = context.getApprovalDAO();
                InfoSessionDAO idao = context.getInfoSessionDAO();
                UserDAO udao = context.getUserDAO();
                User currentUser = udao.getUser(CurrentUser.getId());
                Tuple<InfoSession, EnrollementStatus> lastSession = idao.getLastInfoSession(currentUser);
                if (lastSession == null || lastSession.getSecond() != EnrollementStatus.PRESENT) {
                    flash("danger", "Je bent nog niet aanwezig geweest op een infosessie.");
                    return redirect(routes.InfoSessions.showUpcomingSessions());
                } else {
                    dao.createApproval(currentUser, lastSession.getFirst(), form.get().message);
                    currentUser.setStatus(UserStatus.FULL_VALIDATING); //set to validation
                    udao.updateUser(currentUser); //full update   // TODO: partial update?
                    return redirect(routes.Dashboard.index());
                }
            }
        }
    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result pendingApprovalList() {
        return ok(approvals.render());
    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result pendingApprovalListPaged(int page) {
        DataAccessContext context = DataAccess.getInjectedContext();
        int pageSize = Integer.parseInt(context.getSettingDAO().getSettingForNow("infosessions_page_size")); // should be application constant?
        ApprovalDAO dao = context.getApprovalDAO();
        List<Approval> approvalsList = dao.getApprovals(page, pageSize);
        int amountOfResults = dao.getApprovalCount();
        int amountOfPages = (int) Math.ceil(amountOfResults / (double) pageSize);

        return ok(approvalpage.render(approvalsList, page, amountOfResults, amountOfPages));
    }

    public static class ApprovalAdminModel {
        public String message;
        public String status;
        public boolean sharer;
        public boolean user;

        public enum Action {
            ACCEPT,
            DENY
        }

        public Action getAction() {
            return Enum.valueOf(Action.class, status);
        }

        public String validate() {
            if (getAction() == Action.ACCEPT && !sharer && !user) { //if the user is accepted, but no extra rules specified
                return "Gelieve aan te geven welke rechten deze gebruiker toegewezen krijgt.";
            } else return null;
        }
    }

    // used in injected context
    private static Result approvalForm(Approval ap, DataAccessContext context, Form<ApprovalAdminModel> form, boolean bad) {
        EnrollementStatus status = EnrollementStatus.ABSENT;
        if (ap.getSession() != null) {
            InfoSessionDAO idao = context.getInfoSessionDAO();
            status = idao.getUserEnrollmentStatus(ap.getSession().getId(), ap.getUser().getId());
        }

        if (!bad) {
            return ok(approvaladmin.render(ap, status, checkApprovalConditions(ap.getUser(), context), form));
        } else {
            return badRequest(approvaladmin.render(ap, status, checkApprovalConditions(ap.getUser(), context), form));
        }
    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result approvalDetails(int approvalId) {
        DataAccessContext context = DataAccess.getInjectedContext();
        ApprovalDAO dao = context.getApprovalDAO();
        Approval ap = dao.getApproval(approvalId);
        if (ap == null) {
            flash("danger", "Er is geen aanvraag met deze id.");
            return redirect(routes.InfoSessions.pendingApprovalList());
        } else {
            if (ap.getAdmin() == null) {
                flash("danger", "Gelieve eerst een contractverantwoordelijke op te geven.");
                return redirect(routes.InfoSessions.approvalAdmin(approvalId));
            } else {
                ApprovalAdminModel model = new ApprovalAdminModel();
                model.message = ap.getAdminMessage();
                model.status = (ap.getStatus() == Approval.ApprovalStatus.ACCEPTED || ap.getStatus() == Approval.ApprovalStatus.PENDING
                        ? ApprovalAdminModel.Action.ACCEPT : ApprovalAdminModel.Action.DENY).name();
                Set<UserRole> userRoles = DataAccess.getInjectedContext().getUserRoleDAO().getUserRoles(ap.getUser().getId());

                model.sharer = userRoles.contains(UserRole.CAR_OWNER);
                model.user = userRoles.contains(UserRole.CAR_USER);

                // Get the contact admin
                UserDAO udao = context.getUserDAO();
                ap.setUser(udao.getUser(ap.getUser().getId()));

                return approvalForm(ap, context, Form.form(ApprovalAdminModel.class).fill(model), false);
            }
        }

    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result approvalAdmin(int approvalId) {
        DataAccessContext context = DataAccess.getInjectedContext();
        ApprovalDAO dao = context.getApprovalDAO();
        Approval ap = dao.getApproval(approvalId);
        if (ap == null) {
            flash("danger", "Er is geen aanvraag met deze id.");
            return redirect(routes.InfoSessions.pendingApprovalList());
        } else {

            UserDAO udao = context.getUserDAO();
            ap.setUser(udao.getUser(ap.getUser().getId()));
            EnrollementStatus status = EnrollementStatus.ABSENT;
            if (ap.getSession() != null) {
                InfoSessionDAO idao = context.getInfoSessionDAO();
                status = idao.getUserEnrollmentStatus(ap.getSession().getId(), ap.getUser().getId());
            }

            return ok(setcontractadmin.render(ap, status, ap.getAdmin()));
        }
    }

    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result approvalAdminPost(int id) {
        DynamicForm form = Form.form().bindFromRequest();
        int userId = Integer.valueOf(form.get("manager"));
        DataAccessContext context = DataAccess.getInjectedContext();
        ApprovalDAO adao = context.getApprovalDAO();
        Approval app = adao.getApproval(id);
        if (app == null) {
            flash("danger", "Er is geen aanvraag met deze id.");
            return redirect(routes.InfoSessions.pendingApprovalList());
        } else {
            UserDAO udao = context.getUserDAO();
            User contractManager = udao.getUserPartial(userId);

            if (contractManager != null) {
                Set<UserRole> userRoles = context.getUserRoleDAO().getUserRoles(userId);
                if (userRoles.contains(UserRole.INFOSESSION_ADMIN) || userRoles.contains(UserRole.SUPER_USER)) {
                    // TODO: introduce hasRole method in DAO
                    app.setAdmin(contractManager);
                    adao.setApprovalAdmin(app, contractManager);

                    Notifier.sendContractManagerAssignedMail(app.getUser(), app);
                    flash("success", "De aanvraag werd successvol toegewezen aan " + contractManager);
                    return redirect(routes.InfoSessions.pendingApprovalList());
                } else {
                    flash("danger", contractManager + " heeft geen infosessie beheerdersrechten.");
                    return redirect(routes.InfoSessions.approvalAdmin(id));
                }
            } else {
                flash("danger", "Contractmanager ID bestaat niet.");
                return redirect(routes.InfoSessions.approvalAdmin(id));
            }
        }
    }

    /**
     * Method: POST
     *
     * @param approvalId
     * @return
     */
    @AllowRoles({UserRole.INFOSESSION_ADMIN, UserRole.PROFILE_ADMIN})
    @InjectContext
    public static Result approvalAdminAction(int approvalId) {
        Form<ApprovalAdminModel> form = Form.form(ApprovalAdminModel.class).bindFromRequest();
        DataAccessContext context = DataAccess.getInjectedContext();
        ApprovalDAO dao = context.getApprovalDAO();
        Approval ap = dao.getApproval(approvalId);
        if (ap == null) {
            flash("danger", "Er is geen aanvraag met deze id.");
            return redirect(routes.InfoSessions.pendingApprovalList());
        } else {
            if (form.hasErrors()) {
                return approvalForm(ap, context, form, true);
            }

            ApprovalAdminModel m = form.get();
            ApprovalAdminModel.Action action = m.getAction();

            ap.setAdmin(DataProvider.getUserProvider().getUser());
            ap.setReviewed(new DateTime());
            ap.setAdminMessage(m.message);

            if (action == ApprovalAdminModel.Action.ACCEPT) {
                UserDAO udao = context.getUserDAO();

                ap.setStatus(Approval.ApprovalStatus.ACCEPTED);
                dao.updateApproval(ap);

                // Set contact admin
                User user = udao.getUser(ap.getUser().getId());
                user.setStatus(UserStatus.FULL);
                user.setAgreeTerms(true); //TODO: check if we can accept this earlier, as it is accepted once approval is submitted
                udao.updateUser(user); //full update

                // Add the new user roles
                UserRoleDAO roleDao = context.getUserRoleDAO();
                if (m.sharer)
                    roleDao.addUserRole(ap.getUser().getId(), UserRole.CAR_OWNER);
                if (m.user)
                    roleDao.addUserRole(ap.getUser().getId(), UserRole.CAR_USER);
                Notifier.sendMembershipStatusChanged(ap.getUser(), true, m.message);
                flash("success", "De gebruikersrechten werden succesvol aangepast.");

                return redirect(routes.InfoSessions.pendingApprovalList());
            } else if (action == ApprovalAdminModel.Action.DENY) {
                //TODO Warning, if status was not pending, possibly have to remove user roles
                ap.setStatus(Approval.ApprovalStatus.DENIED);
                dao.updateApproval(ap);
                Notifier.sendMembershipStatusChanged(ap.getUser(), false, m.message);
                flash("success", "De aanvraag werd met succes afgekeurd.");

                return redirect(routes.InfoSessions.pendingApprovalList());
            } else {
                return badRequest("Unspecified.");
            }


        }
    }

    /**
     * Method: GET
     * Returns if the user had been to an infosession
     *
     * @return
     */
    // used in injected context
    // TODO: also used in dashboard
    public static boolean didUserGoToInfoSession() {
        final User user = DataProvider.getUserProvider().getUser();
        InfoSessionDAO dao = DataAccess.getInjectedContext().getInfoSessionDAO();
        final Tuple<InfoSession, EnrollementStatus> enrolled = dao.getLastInfoSession(user);
        return enrolled != null && enrolled.getSecond() == EnrollementStatus.PRESENT && !user.hasFullStatus();
    }

    /*
     * Method: GET
     * Returns the promise of list of the upcoming infosessions. When the user is enrolled already this also includes map data if enabled
     *
     * @return
     */
    /*
    @AllowRoles
    @InjectContext
    // TODO: inject context does not work here

    public static F.Promise<Result> showUpcomingSessionsOriginal() {
        final User user = DataProvider.getUserProvider().getUser();
        InfoSessionDAO dao = DataAccess.getInjectedContext().getInfoSessionDAO();
        final Tuple<InfoSession, EnrollementStatus> enrolled = dao.getLastInfoSession(user);

        final boolean didUserGoToInfoSession = didUserGoToInfoSession();
        boolean showMaps = enrolled != null && "true".equals (DataAccess.getInjectedContext().getSettingDAO().getSettingForNow("show_maps"));
        if (showMaps) {

            return Maps.getLatLongPromise(enrolled.getFirst().getAddress().getId()).map(
                    new F.Function<F.Tuple<Double, Double>, Result>() {
                        public Result apply(F.Tuple<Double, Double> coordinates) {
                            return ok(infosessions.render(enrolled.getFirst(),
                                    coordinates == null ? null : new Maps.MapDetails(coordinates._1, coordinates._2, 14, "Afspraak op " + enrolled.getFirst().getTime().toString("dd-MM-yyyy") + " om " + enrolled.getFirst().getTime().toString("HH:mm")),
                                    didUserGoToInfoSession));
                        }
                    }
            );
        } else {
            return F.Promise.promise(() -> ok(infosessions.render(enrolled == null ? null : enrolled.getFirst(), null, didUserGoToInfoSession)));
        }
    }
    */

    @AllowRoles
    @InjectContext
    public static Result showUpcomingSessions() {
        // TODO: adjust so that it shows a map, like in showUpcomingSessionsOriginal
        User user = DataProvider.getUserProvider().getUser();
        InfoSessionDAO dao = DataAccess.getInjectedContext().getInfoSessionDAO();
        Tuple<InfoSession, EnrollementStatus> tuple = dao.getLastInfoSession(user);
        boolean didUserGoToInfoSession = didUserGoToInfoSession();

        InfoSession enrolled = dao.getAttendingInfoSession(user);

        Iterable<InfoSession> sessions = dao.getInfoSessions(true);
        if (enrolled != null) {
            //TODO: Fix this by also including going count in getAttendingInfoSession (now we fetch it from other list)
            // Hack herpedy derp!!

            for (InfoSession s : sessions) {
                if (enrolled.getId() == s.getId()) {
                    enrolled = s;
                    break;
                }
            }
        }

        return ok(infosessions.render(sessions, tuple == null ? null : tuple.getFirst(), null, didUserGoToInfoSession));

    }

    /**
     * Method: GET*
     *
     * @return A table containing the upcoming sessions
     */
    @AllowRoles({UserRole.INFOSESSION_ADMIN})
    @InjectContext
    public static Result showSessions() {

        User user = DataProvider.getUserProvider().getUser();
        InfoSessionDAO dao = DataAccess.getInjectedContext().getInfoSessionDAO();
        InfoSession enrolled = dao.getAttendingInfoSession(user);

        Iterable<InfoSession> sessions = dao.getInfoSessions(false);
        if (enrolled != null) {
            //TODO: Fix this by also including going count in getAttendingInfoSession (now we fetch it from other list)
            // Hack herpedy derp!!

            for (InfoSession s : sessions) {
                if (enrolled.getId() == s.getId()) {
                    enrolled = s;
                    break;
                }
            }
        }

        return ok(infosessionsAdmin.render(sessions));
    }
}
