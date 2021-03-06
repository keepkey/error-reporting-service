package org.multibit.hd.error_reporting.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import com.yammer.dropwizard.jersey.caching.CacheControl;
import com.yammer.metrics.annotation.ExceptionMetered;
import com.yammer.metrics.annotation.Metered;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.multibit.commons.crypto.PGPUtils;
import org.multibit.hd.common.error_reporting.ErrorReport;
import org.multibit.hd.common.error_reporting.ErrorReportLogEntry;
import org.multibit.hd.common.error_reporting.ErrorReportResult;
import org.multibit.hd.common.error_reporting.ErrorReportStatus;
import org.multibit.hd.error_reporting.ErrorReportingService;
import org.multibit.hd.error_reporting.caches.ErrorReportingResponseCache;
import org.multibit.hd.error_reporting.core.email.Emails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>Resource to provide the following to application:</p>
 * <ul>
 * <li>Provision of error reporting responses</li>
 * <li>Handles decrypting error reports and handing them upstream</li>
 * </ul>
 *
 * @since 0.0.1
 */
@Path("/error-reporting")
public class PublicErrorReportingResource extends BaseResource {

  private static final Logger log = LoggerFactory.getLogger(PublicErrorReportingResource.class);

  private static final ObjectMapper mapper = new ObjectMapper();

  private final Client elasticClient;

  /**
   * The maximum length of the payload (typical value before encryption is 200Kb so compressed should be much smaller)
   */
  private final static int MAX_PAYLOAD_LENGTH = 2_000_000;

  private final char[] password;
  private final byte[] secring;

  private final String SERVICE_PUBLIC_KEY;

  /**
   * Default constructor used by Jersey and reads from the ErrorReportingService
   */
  public PublicErrorReportingResource() {
    this(
      ErrorReportingService.getSecring(),
      ErrorReportingService.getPassword(),
      ErrorReportingService.getServicePublicKey(),
      ErrorReportingService.getElasticClient()
    );
  }

  /**
   * Full constructor used by resource tests
   */
  public PublicErrorReportingResource(byte[] secring, char[] password, String servicePublicKey, Client elasticClient) {

    this.secring = Arrays.copyOf(secring, secring.length);
    this.password = Arrays.copyOf(password, password.length);
    this.SERVICE_PUBLIC_KEY = servicePublicKey;
    this.elasticClient = elasticClient;

  }

  /**
   * Allow an uploader to compare or obtain the error reporting public key
   *
   * @return A localised view containing plain text
   */
  @GET
  @Path("/public-key")
  @Consumes("text/plain")
  @Produces("text/plain")
  @Metered
  @ExceptionMetered
  @CacheControl(maxAge = 1, maxAgeUnit = TimeUnit.DAYS)
  public Response getPublicKey() {
    return Response.ok(SERVICE_PUBLIC_KEY).build();
  }

  /**
   * Allow a client to upload an error report as an ASCII armored payload (useful for REST clients)
   *
   * @param encryptedPayload The encrypted error report payload
   *
   * @return A plain text response
   */
  @POST
  @Consumes("text/plain")
  @Produces("application/json")
  @Metered
  @ExceptionMetered
  @CacheControl(noCache = true)
  public Response submitEncryptedErrorReport(String encryptedPayload) {

    if (Strings.isNullOrEmpty(encryptedPayload)) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }
    if (encryptedPayload.length() > MAX_PAYLOAD_LENGTH) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }

    ErrorReportResult result = processEncryptedErrorReport(encryptedPayload.getBytes(Charsets.UTF_8));

    return Response
      .created(UriBuilder.fromPath("/error-reporting").build())
      .entity(result)
      .build();

  }

  private ErrorReportResult processEncryptedErrorReport(byte[] encryptedPayload) {

    byte[] sha1 = ErrorReportingService.digest(encryptedPayload);
    Optional<ErrorReportResult> cachedResponse = ErrorReportingResponseCache.INSTANCE.getByErrorReportDigest(sha1);

    if (cachedResponse.isPresent()) {
      log.debug("Using cached response");
      return cachedResponse.get();
    }

    ByteArrayOutputStream plainBaos = new ByteArrayOutputStream();
    try {
      // Decrypt the payload (PGP utils require an input stream)
      ByteArrayInputStream encryptedBais = new ByteArrayInputStream(encryptedPayload);
      PGPUtils.decryptFile(encryptedBais, plainBaos, new ByteArrayInputStream(secring), password);
    } catch (Exception e) {
      throw new WebApplicationException(Response.Status.BAD_REQUEST);
    }

    try {
      // Push to ELK and cache the result
      ErrorReportResult result = pushToElk(plainBaos.toByteArray());
      ErrorReportingResponseCache.INSTANCE.put(sha1, result);

      return result;
    } catch (NoNodeAvailableException e) {
      // ELK has gone down - persist the encrypted payload to the file system
      return pushToFileSystem(encryptedPayload);
    }
  }

  /**
   * Persist the encrypted error report to the file system until ELK is running
   *
   * @param encryptedPayload The original encrypted payload
   */
  private ErrorReportResult pushToFileSystem(byte[] encryptedPayload) {

    // Must be new or uncached to be here
    log.debug("No ELK. Persisting to file system.");

    try {
      File errorReportsDirectory = new File(ErrorReportingService.getErrorReportingDirectory().getAbsolutePath() + "/error-reports");
      if (!errorReportsDirectory.exists()) {
        if (!errorReportsDirectory.mkdirs()) {
          log.error("Failed to create backup directory");
          return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
        }
      }

      // Create a suitable unique error report file
      final String errorReportPath = "error-report-" + UUID.randomUUID().toString()+".json.asc";
      File errorReportFile = new File(errorReportsDirectory, errorReportPath);

      // Write the encrypted payload
      Files.write(encryptedPayload, errorReportFile);

      // Error report delivered
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_OK_UNKNOWN);

    } catch (IOException e) {
      log.error("Failed to persist error report.", e);
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

  }

  /**
   * Reduced visibility for testing
   *
   * @param payload The plaintext payload
   *
   * @return The result of the push (e.g. "OK_UNKNOWN")
   */
  protected ErrorReportResult pushToElk(byte[] payload) {

    // Verify the payload is an ErrorReport in JSON
    final ErrorReport errorReport;
    try {
      errorReport = mapper.readValue(payload, ErrorReport.class);
    } catch (IOException e) {
      // User has uploaded something random
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    // Verify the report contains reasonable data
    if (errorReport == null) {
      // User has uploaded something random
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }
    if (Strings.isNullOrEmpty(errorReport.getOsName())) {
      // User has uploaded something random
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }
    if (Strings.isNullOrEmpty(errorReport.getOsArch())) {
      // User has uploaded something random
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }
    if (Strings.isNullOrEmpty(errorReport.getOsVersion())) {
      // User has uploaded something random
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }
    if (Strings.isNullOrEmpty(errorReport.getAppVersion())) {
      // User has uploaded something random
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }
    if (errorReport.getLogEntries() == null) {
      // User has uploaded something random
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }
    if (errorReport.getLogEntries().isEmpty()) {
      // User has uploaded something random
      return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
    }

    // Create a short UUID for this log
    String id = UUID.randomUUID().toString().substring(0, 7);

    String appVersion = errorReport.getAppVersion();
    String osArch = errorReport.getOsArch();
    String osVersion = errorReport.getOsVersion();
    String osName = errorReport.getOsName();
    String userNotes = errorReport.getUserNotes();

    ErrorReport summary = new ErrorReport();
    summary.setAppVersion(appVersion);
    summary.setOsArch(osArch);
    summary.setOsVersion(osVersion);
    summary.setOsName(osName);
    summary.setUserNotes(userNotes);

    // Push the payload to Elasticsearch in parts under its own index for easier analysis
    if (elasticClient != null) {
      IndexResponse response;
      try {
        // Elasticsearch prefers a unique document type per index
        response = elasticClient
          .prepareIndex("error-report-summary-" + id, "error-report-summary", id)
          .setSource(mapper.writeValueAsString(summary))
          .execute()
          .actionGet();

        // Write each log entry under its own id within the index
        for (int i = 0; i < errorReport.getLogEntries().size(); i++) {
          response = elasticClient
            .prepareIndex("error-report-entries-" + id, "log-entry", String.valueOf(i))
            .setSource(mapper.writeValueAsString(errorReport.getLogEntries().get(i)))
            .execute()
            .actionGet();
        }

      } catch (JsonProcessingException e) {
        // Unable to re-marshal
        log.error("Unable to re-marshal the error report. This indicates a code problem.");
        return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
      }

      if (response == null) {
        // Elasticsearch failed
        return new ErrorReportResult(ErrorReportStatus.UPLOAD_FAILED);
      }
    }

    // Must be OK to be here
    ErrorReportResult result = new ErrorReportResult(ErrorReportStatus.UPLOAD_OK_UNKNOWN);
    result.setId(id);
    result.setUri(URI.create("https://multibit.org"));

    log.info("Posted error-report under '{}'", id);

    // Allow external configuration to switch this off if traffic gets too high
    if (ErrorReportingService.getErrorReportingConfiguration().isSendEmail()) {
      // Send an email to alert support of an uploaded error report
      // This occurs asynchronously
      try {
        // Get the first stack trace and count how many are present
        List<ErrorReportLogEntry> logEntries = errorReport.getLogEntries();

        String firstStackTrace = "";
        int numberOfStackTraces = 0;

        if (logEntries != null) {
          for (ErrorReportLogEntry logEntry : logEntries) {
            if (logEntry.getStackTrace() != null && logEntry.getStackTrace().length() > 0) {
              numberOfStackTraces++;
              if ("".equals(firstStackTrace)) {
                // Remember the first
                firstStackTrace = logEntry.getStackTrace();
              }
            }
          }
        }
        final String newLine = "\n";

        StringBuilder builder = new StringBuilder();
        builder.append("New error report uploaded. Id: ").append(id).append(newLine).append(newLine);

        builder.append("MultiBit HD version: ").append(appVersion).append(newLine);
        builder.append("Operating system: ").append(osArch).append(" ").append(osVersion).append(" ").append(osName).append(newLine).append(newLine);

        String userNotePresent = (userNotes == null || userNotes.length() == 0) ? "No user notes" : "User notes length: " + userNotes.length();
        builder.append(userNotePresent).append(newLine).append(newLine);

        builder.append("Number of stack traces: ").append(numberOfStackTraces).append(newLine);
        builder.append("First stack trace:").append("\n").append(firstStackTrace).append(newLine);

        Emails.sendSupportEmail(builder.toString());
      } catch (IllegalStateException e) {
        log.error("Failed to send email", e);
      }
    }

    return result;
  }

}
