package extractor;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import com.amazonaws.services.lambda.runtime.LambdaLogger;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.json.simple.JSONObject;
import org.xml.sax.SAXException;

public class TextExtractor {

    private LambdaLogger logger;

    public TextExtractor(LambdaLogger logger) {
        this.logger = logger;
    }

    public String extract(String bucket, String key, InputStream objectData) {
        try {
            logger.log("Extracting text with Tika");
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

            Metadata tikaMetadata = new Metadata();
            try {
                // for synthetic transactions
                if( key.toLowerCase().endsWith("tika.exception.testing.pdf")) {
                throw new TikaException("Test Tika Exception");
                }
                parser.parse(objectData, handler, tikaMetadata, parseContext);
                extractedText = sw.toString();
            } catch( TikaException e) {
                logger.log("TikaException thrown while parsing: " + e.getLocalizedMessage());
                return assembleExceptionResult(bucket, key, e);
            }
            logger.log("Tika parsing success");
            return assembleExtractionResult(bucket, key, extractedText, tikaMetadata);
        } catch (IOException | TransformerConfigurationException | SAXException e) {
            logger.log("Runtime error parsing document: " + e.getLocalizedMessage());
            throw new RuntimeException(e);
        }
    }

    private String assembleExtractionResult(String bucket, String key, String extractedText, Metadata tikaMetadata) {

        JSONObject extractJson = new JSONObject();

        String contentType = tikaMetadata.get("Content-Type");
        contentType = contentType != null ? contentType : "content/unknown";

        String contentLength = tikaMetadata.get("Content-Length");
        contentLength = contentLength != null ? contentLength : "0";

        extractJson.put("ExtractorVersion", "0.3.0");
        extractJson.put("Exception", null);
        extractJson.put("FilePath", "s3://" + bucket + "/" + key);
        extractJson.put("Text", extractedText);
        extractJson.put("ContentType", contentType);
        extractJson.put("ContentLength", contentLength);

        JSONObject metadataJson = new JSONObject();

        for (String name : tikaMetadata.names()) {
            String[] elements = tikaMetadata.getValues(name);
            String joined = String.join(", ", elements);
            metadataJson.put(name, joined);
        }

        extractJson.put("Metadata", metadataJson);

        return extractJson.toJSONString();
    }

    private String assembleExceptionResult(String bucket, String key, Exception e) {
        JSONObject exceptionJson = new JSONObject();

        exceptionJson.put("ExtractorVersion", "0.3.0");
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