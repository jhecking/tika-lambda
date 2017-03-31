package assingmentmarker.extractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.net.URLDecoder;
import java.util.Iterator;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.TransformerConfigurationException;
import org.xml.sax.SAXException;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;

import org.json.simple.JSONObject;

public class TikaLambdaHandler implements RequestHandler<S3Event, String> {

    private LambdaLogger _logger;

    public String handleRequest(S3Event s3event, Context context) {
        _logger = context.getLogger();
        _logger.log("Received S3 Event: " + s3event.toJson());

        try {
            S3EventNotificationRecord record = s3event.getRecords().get(0);

            String bucket = record.getS3().getBucket().getName();

            // Object key may have spaces or unicode non-ASCII characters.
            String key = URLDecoder.decode(record.getS3().getObject().getKey().replace('+', ' '), "UTF-8");

            // Short-circuit ignore .extract files because they have already been extracted, this prevents an endless loop
            if (key.toLowerCase().endsWith(".extract")) {
              _logger.log("Ignoring extract file " + key);
              return "Ignored";
            }

            AmazonS3 s3Client = new AmazonS3Client();
            S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucket, key));

            try (InputStream objectData = s3Object.getObjectContent()) {
                String extractJson = doTikaStuff(bucket, key, objectData);

                byte[] extractBytes = extractJson.getBytes(Charset.forName("UTF-8"));
                int extractLength = extractBytes.length;

                ObjectMetadata metaData = new ObjectMetadata();
                metaData.setContentLength(extractLength);

                _logger.log("Saving extract file to S3");
                InputStream inputStream = new ByteArrayInputStream(extractBytes);
                s3Client.putObject(bucket, key + ".extract", inputStream, metaData);
            }
        } catch (IOException | TransformerConfigurationException | SAXException e) {
            _logger.log("Exception: " + e.getLocalizedMessage());
            throw new RuntimeException(e);
        }
        return "Success";
    }

    private String doTikaStuff(String bucket, String key, InputStream objectData) throws IOException, TransformerConfigurationException, SAXException {
      _logger.log("Extracting text with Tika");
      String extractedText = "";

      SAXTransformerFactory factory = (SAXTransformerFactory)SAXTransformerFactory.newInstance();
      TransformerHandler handler = factory.newTransformerHandler();
      handler.getTransformer().setOutputProperty(OutputKeys.METHOD, "text");
      handler.getTransformer().setOutputProperty(OutputKeys.INDENT, "yes");
      StringWriter sw = new StringWriter();
      handler.setResult(new StreamResult(sw));
      AutoDetectParser parser = new AutoDetectParser();
      ParseContext parseContext = new ParseContext();
      parseContext.set(Parser.class, parser);

      Tika tika = new Tika();
      Metadata tikaMetadata = new Metadata();
      try {
        // for synthetic transactions
        if( key.toLowerCase().endsWith("tika.exception.testing.pdf")) {
          throw new TikaException("Test Tika Exception");
        }
        parser.parse(objectData, handler, tikaMetadata, parseContext);
        extractedText = sw.toString();
      } catch( TikaException e) {
        _logger.log("TikaException thrown while parsing: " + e.getLocalizedMessage());
        return assembleExceptionResult(bucket, key, e);
      }
      _logger.log("Tika parsing success");
      return assembleExtractionResult(bucket, key, extractedText, tikaMetadata);
    }

    private String assembleExtractionResult(String bucket, String key, String extractedText, Metadata tikaMetadata) {

      JSONObject extractJson = new JSONObject();

      String contentType = tikaMetadata.get("Content-Type");
      contentType = contentType != null ? contentType : "content/unknown";

      String contentLength = tikaMetadata.get("Content-Length");
      contentLength = contentLength != null ? contentLength : "0";

      extractJson.put("Exception", null);
      extractJson.put("FilePath", "s3://" + bucket + "/" + key);
      extractJson.put("Text", extractedText);
      extractJson.put("ContentType", contentType);
      extractJson.put("ContentLength", contentLength);

      JSONObject metadataJson = new JSONObject();

      for( String name : tikaMetadata.names() ){
        String[] elements = tikaMetadata.getValues(name);
        String joined = String.join(", ", elements);
        metadataJson.put(name, joined);
      }

      extractJson.put("Metadata", metadataJson);

      return extractJson.toJSONString();
    }

    private String assembleExceptionResult(String bucket, String key, Exception e){
      JSONObject exceptionJson = new JSONObject();

      exceptionJson.put("Exception", e.getLocalizedMessage());
      exceptionJson.put("FilePath", "s3://" + bucket + "/" + key);
      exceptionJson.put("ContentType", "unknown");
      exceptionJson.put("ContentLength", "0");
      exceptionJson.put("Text", "");

      JSONObject metadataJson = new JSONObject();
      metadataJson.put("resourceName", "s3://" + bucket + "/" + key);

      exceptionJson.put("Metadata", metadataJson);

      return exceptionJson.toJSONString();
    }
}
