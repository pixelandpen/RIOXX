package org.dspace.app.xmlui.aspect.submission.submit;

import org.apache.avalon.framework.parameters.Parameters;
import org.apache.cocoon.ProcessingException;
import org.apache.cocoon.environment.ObjectModelHelper;
import org.apache.cocoon.environment.Request;
import org.apache.cocoon.environment.SourceResolver;
import org.apache.log4j.Logger;
import org.dspace.app.xmlui.aspect.submission.AbstractSubmissionStep;
import org.dspace.app.xmlui.utils.UIException;
import org.dspace.app.xmlui.wing.Message;
import org.dspace.app.xmlui.wing.WingException;
import org.dspace.app.xmlui.wing.element.*;
import org.dspace.authority.ProjectAuthorityValue;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.Metadatum;
import org.dspace.content.authority.ChoiceAuthorityManager;
import org.dspace.content.authority.MetadataAuthorityManager;
import org.dspace.core.ConfigurationManager;
import org.dspace.project.ProjectService;
import org.dspace.utils.DSpace;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

/**
 * Created by Philip Vissenaekens (philip at atmire dot com)
 * Date: 03/09/15
 * Time: 10:51
 */
public class ProjectStep extends AbstractSubmissionStep {
    private static Logger log = Logger.getLogger(ProjectStep.class);

    protected static final Message T_head = message("xmlui.Submission.submit.ProjectStep.head");
    protected static final Message T_required_field = message("xmlui.Submission.submit.DescribeStep.required_field");

    protected static final Message T_project_label = message("xmlui.Submission.submit.ProjectStep.project.label");
    protected static final Message T_funder_label = message("xmlui.Submission.submit.ProjectStep.funder.label");
    protected static final Message T_project_funder_hint = message("xmlui.Submission.submit.ProjectStep.project_funder.hint");
    protected static final Message T_remove = message("xmlui.Submission.submit.ProjectStep.remove");
    protected static final Message T_create_project_error = message("xmlui.Submission.submit.ProjectStep.create_project_error");
    protected static final Message T_lookup_project_error = message("xmlui.Submission.submit.ProjectStep.lookup_project_error");

    private ProjectAuthorityValue newProject;

    @Override
    public void setup(SourceResolver resolver, Map objectModel, String src, Parameters parameters) throws ProcessingException, SAXException, IOException {
        super.setup(resolver, objectModel, src, parameters);
        Request request = ObjectModelHelper.getRequest(objectModel);
        newProject = (ProjectAuthorityValue) request.getSession().getAttribute("newProject");
    }

    public void addPageMeta(PageMeta pageMeta) throws SAXException, WingException,
            UIException, SQLException, IOException, AuthorizeException
    {
        super.addPageMeta(pageMeta);
        int collectionID = submission.getCollection().getID();
        pageMeta.addMetadata("choice", "collection").addContent(String.valueOf(collectionID));
        pageMeta.addMetadata("stylesheet", "screen", "datatables", true).addContent("../../static/Datatables/DataTables-1.8.0/media/css/datatables.css");
        pageMeta.addMetadata("javascript", "static", "datatables", true).addContent("static/Datatables/DataTables-1.8.0/media/js/jquery.dataTables.min.js");
        pageMeta.addMetadata("stylesheet", "screen", "person-lookup", true).addContent("../../static/css/authority/person-lookup.css");
        pageMeta.addMetadata("javascript", null, "person-lookup", true).addContent("../../static/js/person-lookup.js");


        String jumpTo = submissionInfo.getJumpToField();
        if (jumpTo != null)
        {
            pageMeta.addMetadata("page", "jumpTo").addContent(jumpTo);
        }
    }

    @Override
    public void addBody(Body body) throws SAXException, WingException, UIException, SQLException, IOException, AuthorizeException, ProcessingException {
        Item item = submission.getItem();
        Collection collection = submission.getCollection();

        String actionURL = contextPath + "/handle/" + collection.getHandle() + "/submit/" + knot.getId() + ".continue";
        Division div = body.addInteractiveDivision("submit-describe", actionURL, Division.METHOD_POST, "primary submission");
        div.setHead(T_submission_head);
        addSubmissionProgressList(div);

        List form = div.addList("submit-project", List.TYPE_FORM);
        form.setHead(T_head);

        String fieldName = "rioxxterms_identifier_project";
        renderOneboxField(form, fieldName, false, true, T_project_label, null);

        fieldName = "rioxxterms_funder";
        renderOneboxField(form, fieldName, true, false, T_funder_label, null);

        form.addItem("project_funder_help","").addContent(T_project_funder_hint.parameterize(ConfigurationManager.getProperty("mail.admin")));

        Metadatum[] dcValues = item.getMetadata("rioxxterms", "identifier", "project", Item.ANY);

        form.addItem().addButton("submit_add").setValue("Add");

        renderResults(div, dcValues);

        addControlButtons(div.addList("submit-project-part2", List.TYPE_FORM));
    }

    private void renderResults(Division div, Metadatum[] dcValues) throws WingException {
        if (dcValues != null && dcValues.length > 0) {
            Division projectDiv = div.addDivision("projects");

            Table table = projectDiv.addTable("project-table", 3, dcValues.length + 1);
            Row header = table.addRow(Row.ROLE_HEADER);
            header.addCell().addContent(T_project_label);
            header.addCell().addContent(T_funder_label);
            header.addCell().addContent("");

            ProjectService projectService = new DSpace().getServiceManager().getServiceByName("ProjectService", ProjectService.class);

            int count = 0;

            for (Metadatum dcValue : dcValues) {
                ProjectAuthorityValue project;
                if (newProject != null && newProject.getId().equals(dcValue.authority)) {
                    project = newProject;
                } else {
                    project = projectService.getProjectByAuthorityId(context, dcValue.authority);
                }

                if (project != null) {
                    Row row = table.addRow();
                    row.addCell().addContent(project.getValue());
                    row.addCell().addContent(project.getFunderAuthorityValue().getValue());
                    row.addCell().addButton("submit_remove_project_" + count).setValue(T_remove);
                }

                count++;
            }
        }
    }

    private void renderOneboxField(List form, String fieldName, boolean readOnly, boolean required, Message label, Message hint) throws WingException {
        org.dspace.app.xmlui.wing.element.Item formItem = form.addItem();
        Text text = formItem.addText(fieldName, "submit-text");

        // Setup the select field
        text.setLabel(label);
        if(hint!=null) {
            text.setHelp(hint);
        }
        String fieldKey = MetadataAuthorityManager.makeFieldKey("rioxxterms", "identifier", "project");
        text.setAuthorityControlled();
        text.setAuthorityRequired(MetadataAuthorityManager.getManager().isAuthorityRequired(fieldKey));

        if (ChoiceAuthorityManager.getManager().isChoicesConfigured(fieldKey)) {
            text.setChoices(fieldKey);
            text.setChoicesPresentation(ChoiceAuthorityManager.getManager().getPresentation(fieldKey));
            text.setChoicesClosed(ChoiceAuthorityManager.getManager().isClosed(fieldKey));
        }

        if (readOnly) {
            text.setDisabled();
        }

        if (required) {
            text.setRequired();
        }

        if (isFieldInError(fieldName)) {
            if (this.errorFlag == org.dspace.submit.step.ProjectStep.CREATE_PROJECT_ERROR) {
                text.addError(T_create_project_error);

            } else if (this.errorFlag == org.dspace.submit.step.ProjectStep.LOOKUP_PROJECT_ERROR) {
                text.addError(T_lookup_project_error);
            } else {
                text.addError(T_required_field);
            }
        }

    }


    /**
     * Check if the given fieldname is listed as being in error.
     *
     * @param fieldName
     * @return
     */
    private boolean isFieldInError(String fieldName) {
        return (this.errorFields.contains(fieldName));
    }

    @Override
    public List addReviewSection(List reviewList) throws SAXException, WingException, UIException, SQLException, IOException, AuthorizeException {
        List projectSection = reviewList.addList("submit-review-" + this.stepAndPage, List.TYPE_FORM);
        projectSection.setHead(T_head);

        Metadatum[] dcValues = submission.getItem().getMetadata("rioxxterms", "identifier", "project", Item.ANY);

        for (Metadatum dcValue : dcValues) {
            org.dspace.app.xmlui.wing.element.Item project = projectSection.addItem();

            ProjectService projectService = new DSpace().getServiceManager().getServiceByName("ProjectService", ProjectService.class);

            try {
                ProjectAuthorityValue projectAuthorityValue = projectService.getProjectByAuthorityId(context, dcValue.authority);

                project.addContent(projectAuthorityValue.getValue() + " - " + projectAuthorityValue.getFunderAuthorityValue().getValue());
            }
            catch (IllegalArgumentException e){
                log.error(e.getMessage(),e);
            }
        }
        return projectSection;
    }
}
