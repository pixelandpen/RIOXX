/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 * <p/>
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.util;

import com.lyncode.xoai.dataprovider.xml.xoai.Element;
import com.lyncode.xoai.dataprovider.xml.xoai.Metadata;
import com.lyncode.xoai.util.Base64Utils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.dspace.authority.*;
import org.dspace.authority.orcid.OrcidAuthorityValue;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.AuthorizeManager;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.Metadatum;
import org.dspace.content.authority.Choices;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.Utils;
import org.dspace.eperson.Group;
import org.dspace.utils.DSpace;
import org.dspace.xoai.data.DSpaceItem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 *
 * @author Lyncode Development Team <dspace@lyncode.com>
 */
@SuppressWarnings("deprecation")
public class ItemUtils {
	private static Logger log = LogManager
			.getLogger(ItemUtils.class);

	private static Element getElement(List<Element> list, String name) {
		for (Element e : list)
			if (name.equals(e.getName()))
				return e;

		return null;
	}

	private static Element create(String name) {
		Element e = new Element();
		e.setName(name);
		return e;
	}

	private static Element.Field createValue(
			String name, String value) {
		Element.Field e = new Element.Field();
		e.setValue(value);
		e.setName(name);
		return e;
	}

	public static Metadata retrieveMetadata(Item item) {
		Metadata metadata;

		//DSpaceDatabaseItem dspaceItem = new DSpaceDatabaseItem(item);

		// read all metadata into Metadata Object
		metadata = new Metadata();
		Metadatum[] vals = item.getMetadata(Item.ANY, Item.ANY, Item.ANY, Item.ANY);
		for (Metadatum val : vals) {
			Element valueElem = null;
			Element schema = getElement(metadata.getElement(), val.schema);
			if (schema == null) {
				schema = create(val.schema);
				metadata.getElement().add(schema);
			}
			valueElem = schema;

			// Has element.. with XOAI one could have only schema and value
			if (val.element != null && !val.element.equals("")) {
				Element element = getElement(schema.getElement(),
						val.element);
				if (element == null) {
					element = create(val.element);
					schema.getElement().add(element);
				}
				valueElem = element;

				// Qualified element?
				if (val.qualifier != null && !val.qualifier.equals("")) {
					Element qualifier = getElement(element.getElement(),
							val.qualifier);
					if (qualifier == null) {
						qualifier = create(val.qualifier);
						element.getElement().add(qualifier);
					}
					valueElem = qualifier;
				}

			}

			// Language?
			if (val.language != null && !val.language.equals("")) {
				Element language = getElement(valueElem.getElement(),
						val.language);
				if (language == null) {
					language = create(val.language);
					valueElem.getElement().add(language);
				}
				valueElem = language;
			} else {
				Element language = getElement(valueElem.getElement(),
						"none");
				if (language == null) {
					language = create("none");
					valueElem.getElement().add(language);
				}
				valueElem = language;
			}

			valueElem.getField().add(createValue("value", val.value));
			if (val.authority != null) {
				valueElem.getField().add(createValue("authority", val.authority));
				if (val.confidence != Choices.CF_NOVALUE)
					valueElem.getField().add(createValue("confidence", val.confidence + ""));
			}
			AuthorityValueFinder authorityValueFinder = new AuthorityValueFinder();
			try {
				Context context = new Context();


				AuthorityValue authorityValue = authorityValueFinder.findByUID(context, val.authority);
				context.abort();
				if (authorityValue!=null) {
					if (authorityValue instanceof FunderAuthorityValue) {
						String id = ((FunderAuthorityValue) authorityValue).getFunderID();
						valueElem.getField().add(createValue("authorityID", "http://dx.doi.org/" + id));

					} else if (authorityValue instanceof OrcidAuthorityValue) {
						String id = ((OrcidAuthorityValue) authorityValue).getOrcid_id();
						valueElem.getField().add(createValue("authorityID", "http://orcid.org/"+id));
					}
					else if (authorityValue instanceof ProjectAuthorityValue){
						String funderAuthorityId = ((ProjectAuthorityValue) authorityValue).getFunderAuthorityValue().getId();
						valueElem.getField().add(createValue("funderAuthorityID", funderAuthorityId));
				}
				}


			} catch (SQLException e) {
				e.printStackTrace();
			}


		}
		// Done! Metadata has been read!
		// Now adding bitstream info
		Element bundles = create("bundles");
		metadata.getElement().add(bundles);

		Bundle[] bs;
		try {
			bs = item.getBundles();
			for (Bundle b : bs) {
				Element bundle = create("bundle");
				bundles.getElement().add(bundle);
				bundle.getField()
						.add(createValue("name", b.getName()));

				Element bitstreams = create("bitstreams");
				bundle.getElement().add(bitstreams);
				Bitstream[] bits = b.getBitstreams();



				for (Bitstream bts : bits) {
					boolean primary=false;
                    // Check if current bitstream is in original bundle + 1 of the 2 following
                    // Bitstream = primary bitstream in bundle -> true
                    // No primary bitstream found in bundle-> only the first one gets flagged as "primary"
                    if (b.getName().equals("ORIGINAL") && (b.getPrimaryBitstreamID() == bts.getID() || b.getPrimaryBitstreamID() == -1 && bts.getID() == bits[0].getID()))
						primary=true;
					Bitstream  bit=bts;

					if (bit != null) {
						Element bitstream = create("bitstream");
						bitstreams.getElement().add(bitstream);
						String url = "";
						String bsName = bit.getName();
						String sid = String.valueOf(bit.getSequenceID());
						String baseUrl = ConfigurationManager.getProperty("oai",
								"bitstream.baseUrl");
						String handle = null;
						// get handle of parent Item of this bitstream, if there
						// is one:
						Bundle[] bn = bit.getBundles();
						if (bn.length > 0) {
							Item bi[] = bn[0].getItems();
							if (bi.length > 0) {
								handle = bi[0].getHandle();
							}
						}
						if (bsName == null) {
							String ext[] = bit.getFormat().getExtensions();
							bsName = "bitstream_" + sid
									+ (ext.length > 0 ? ext[0] : "");
						}
						if (handle != null && baseUrl != null) {
							url = baseUrl + "/bitstream/"
									+ handle + "/"
									+ sid + "/"
									+ URLUtils.encode(bsName);
						} else {
							url = URLUtils.encode(bsName);
						}

						String cks = bit.getChecksum();
						String cka = bit.getChecksumAlgorithm();
						String oname = bit.getSource();
						String name = bit.getName();
						String description = bit.getDescription();

						addEmbargoField(bit, bitstream);

						if (name != null)
							bitstream.getField().add(
									createValue("name", name));
						if (oname != null)
							bitstream.getField().add(
									createValue("originalName", name));
						if (description != null)
							bitstream.getField().add(
									createValue("description", description));
						bitstream.getField().add(
								createValue("format", bit.getFormat()
										.getMIMEType()));
						bitstream.getField().add(
								createValue("size", "" + bit.getSize()));
						bitstream.getField().add(createValue("url", url));
						bitstream.getField().add(
								createValue("checksum", cks));
						bitstream.getField().add(
								createValue("checksumAlgorithm", cka));
						bitstream.getField().add(
								createValue("sid", bit.getSequenceID()
										+ ""));
						bitstream.getField().add(
								createValue("primary", primary
										+ ""));
					}
				}


			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		}


		// Other info
		Element other = create("others");

		other.getField().add(
				createValue("handle", item.getHandle()));
		other.getField().add(
				createValue("identifier", DSpaceItem.buildIdentifier(item.getHandle())));
		other.getField().add(
				createValue("lastModifyDate", item
						.getLastModified().toString()));
		metadata.getElement().add(other);


		// Repository Info
		Element repository = create("repository");
		repository.getField().add(
				createValue("name",
						ConfigurationManager.getProperty("dspace.name")));
		repository.getField().add(
				createValue("mail",
						ConfigurationManager.getProperty("mail.admin")));
		metadata.getElement().add(repository);

		// Licensing info
		Element license = create("license");
		Bundle[] licBundles;
		try {
			licBundles = item.getBundles(Constants.LICENSE_BUNDLE_NAME);
			if (licBundles.length > 0) {
				Bundle licBundle = licBundles[0];
				Bitstream[] licBits = licBundle.getBitstreams();
				if (licBits.length > 0) {
					Bitstream licBit = licBits[0];
					InputStream in;
					try {
						in = licBit.retrieve();
						ByteArrayOutputStream out = new ByteArrayOutputStream();
						Utils.bufferedCopy(in, out);
						license.getField().add(
								createValue("bin",
										Base64Utils.encode(out.toString())));
						metadata.getElement().add(license);
					} catch (AuthorizeException e) {
						log.warn(e.getMessage(), e);
					} catch (IOException e) {
						log.warn(e.getMessage(), e);
					} catch (SQLException e) {
						log.warn(e.getMessage(), e);
					}

				}
			}
		} catch (SQLException e1) {
			log.warn(e1.getMessage(), e1);
		}

		return metadata;
	}

	private static void addEmbargoField(Bitstream bit, Element bitstream) throws SQLException {
		Context context = new Context();

		List<ResourcePolicy> policies = AuthorizeManager.getPoliciesActionFilter(context, bit, Constants.READ);
		Group group = Group.find(context, Group.ANONYMOUS_ID);

		for (ResourcePolicy policy : policies) {
			if (group.equals(policy.getGroup())) {
				Date startDate = policies.get(0).getStartDate();

				if (startDate != null && startDate.after(new Date())) {
					SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
					bitstream.getField().add(
							createValue("embargo", formatter.format(startDate)));
				}
			}
		}
		context.abort();
	}
}
