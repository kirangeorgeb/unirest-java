/**
 * The MIT License
 *
 * Copyright for portions of unirest-java are held by Kong Inc (c) 2013.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package kong.unirest.java.multi;


import kong.unirest.UnirestException;

import java.io.FileNotFoundException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * A {@code BodyPublisher} implementing the multipart request type.
 *
 * @see <a href="https://tools.ietf.org/html/rfc2046#section-5.1">RFC 2046 Multipart Media Type</a>
 */
public final class MultipartBodyPublisher implements BodyPublisher {

    // An executor that executes the runnable in the calling thread.
    public static final Executor SYNC_EXECUTOR = Runnable::run;
    private static final long UNKNOWN_LENGTH = -1;
    private static final long UNINITIALIZED_LENGTH = -2;

    private static final String BOUNDARY_ATTRIBUTE = "boundary";

    private final List<MultipartBodyPublisher.Part> parts;
    private final MediaType mediaType;
    private long contentLength;

    private MultipartBodyPublisher(List<MultipartBodyPublisher.Part> parts, MediaType mediaType) {
        this.parts = parts;
        this.mediaType = mediaType;
        contentLength = UNINITIALIZED_LENGTH;
    }

    public static PartPublisher ofMediaType(BodyPublisher bodyPublisher, MediaType mediaType) {
        return new PartPublisher(bodyPublisher, mediaType);
    }

    /** Returns the boundary of this multipart body. */
    public String boundary() {
        return mediaType.parameters().get(BOUNDARY_ATTRIBUTE);
    }

    /** Returns an immutable list containing this body's parts. */
    public List<MultipartBodyPublisher.Part> parts() {
        return parts;
    }


    public MediaType mediaType() {
        return mediaType;
    }

    @Override
    public long contentLength() {
        long len = contentLength;
        if (len == UNINITIALIZED_LENGTH) {
            len = computeLength();
            contentLength = len;
        }
        return len;
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
        requireNonNull(subscriber);
        new MultipartSubscription(this, subscriber).signal(true); // apply onSubscribe
    }

    private long computeLength() {
        long lengthOfParts = 0L;
        String boundary = boundary();
        StringBuilder headings = new StringBuilder();
        for (int i = 0, sz = parts.size(); i < sz; i++) {
            MultipartBodyPublisher.Part part = parts.get(i);
            long partLength = part.bodyPublisher().contentLength();
            if (partLength < 0) {
                return UNKNOWN_LENGTH;
            }
            lengthOfParts += partLength;
            // Append preceding boundary + part header
            BoundaryAppender.get(i, sz).append(headings, boundary);
            appendPartHeaders(headings, part);
            headings.append("\r\n");
        }
        BoundaryAppender.LAST.append(headings, boundary);
        // Use headings' utf8-encoded length
        return lengthOfParts + UTF_8.encode(CharBuffer.wrap(headings)).remaining();
    }

    public static void appendPartHeaders(StringBuilder target, MultipartBodyPublisher.Part part) {
        part.headers().map().forEach((n, vs) -> vs.forEach(v -> appendHeader(target, n, v)));
        BodyPublisher publisher = part.bodyPublisher();
        if (publisher instanceof PartPublisher) {
            appendHeader(target, "Content-Type", ((PartPublisher) publisher).mediaType().toString());
        }
    }

    private static void appendHeader(StringBuilder target, String name, String value) {
        target.append(name).append(": ").append(value).append("\r\n");
    }

    /** Returns a new {@code MultipartBodyPublisher.Builder}. */
    public static MultipartBodyPublisher.Builder newBuilder() {
        return new MultipartBodyPublisher.Builder();
    }

    /** Represents a part in a multipart request body. */
    public static final class Part {

        private final HttpHeaders headers;
        private final BodyPublisher bodyPublisher;

        Part(HttpHeaders headers, BodyPublisher bodyPublisher) {
            requireNonNull(headers, "headers");
            requireNonNull(bodyPublisher, "bodyPublisher");

            this.headers = headers;
            this.bodyPublisher = bodyPublisher;
        }

        /** Returns the headers of this part. */
        public HttpHeaders headers() {
            return headers;
        }

        /** Returns the {@code BodyPublisher} that publishes this part's content. */
        public BodyPublisher bodyPublisher() {
            return bodyPublisher;
        }

        public static MultipartBodyPublisher.Part create(HttpHeaders headers, BodyPublisher bodyPublisher) {
            return new MultipartBodyPublisher.Part(headers, bodyPublisher);
        }

    }


    public static final class Builder {

        private static final String MULTIPART_TYPE = "multipart";
        private static final String FORM_DATA_SUBTYPE = "form-data";

        private final List<MultipartBodyPublisher.Part> parts;
        private MediaType mediaType;

        Builder() {
            parts = new ArrayList<>();
            mediaType = MediaType.of(MULTIPART_TYPE, FORM_DATA_SUBTYPE);
        }

        /**
         * Adds the given part.
         *
         * @param part the part
         */
        public MultipartBodyPublisher.Builder part(MultipartBodyPublisher.Part part) {
            requireNonNull(part);
            parts.add(part);
            return this;
        }

        /**
         * Adds a form field with the given name and body.
         *
         * @param name the field's name
         * @param bodyPublisher the field's body publisher
         */
        public MultipartBodyPublisher.Builder formPart(String name, BodyPublisher bodyPublisher) {
            requireNonNull(name, "name");
            requireNonNull(bodyPublisher, "body");
            return part(MultipartBodyPublisher.Part.create(getFormHeaders(name, null), bodyPublisher));
        }

        /**
         * Adds a form field with the given name, filename and body.
         *
         * @param name the field's name
         * @param filename the field's filename
         * @param body the field's body publisher
         */
        public MultipartBodyPublisher.Builder formPart(String name, String filename, BodyPublisher body) {
            requireNonNull(name, "name");
            requireNonNull(filename, "filename");
            requireNonNull(body, "body");
            return part(MultipartBodyPublisher.Part.create(getFormHeaders(name, filename), body));
        }

        /**
         * Adds a {@code text/plain} form field with the given name and value. {@code UTF-8} is used for
         * encoding the field's body.
         *
         * @param name the field's name
         * @param value an object whose string representation is used as the value
         */
        public MultipartBodyPublisher.Builder textPart(String name, Object value) {
            return textPart(name, value, UTF_8);
        }

        /**
         * Adds a {@code text/plain} form field with the given name and value using the given charset
         * for encoding the field's body.
         *
         * @param name the field's name
         * @param value an object whose string representation is used as the value
         * @param charset the charset for encoding the field's body
         */
        public MultipartBodyPublisher.Builder textPart(String name, Object value, Charset charset) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            requireNonNull(charset, "charset");
            return formPart(name, BodyPublishers.ofString(value.toString(), charset));
        }



        /**
         * Adds a file form field with given name, file and media type. The field's filename property
         * will be that of the given path's {@link Path#getFileName() filename compontent}.
         *
         * @param name the field's name
         * @param file the file's path
         * @param mediaType the part's media type
         * @throws FileNotFoundException if a file with the given path cannot be found
         */
        public MultipartBodyPublisher.Builder filePart(String name, Path file, MediaType mediaType)
                throws FileNotFoundException {
            requireNonNull(name, "name");
            requireNonNull(file, "file");
            requireNonNull(mediaType, "mediaType");
            Path filenameComponent = file.getFileName();
            String filename = filenameComponent != null ? filenameComponent.toString() : "";
            PartPublisher publisher =
                    ofMediaType(BodyPublishers.ofFile(file), mediaType);
            return formPart(name, filename, publisher);
        }

        /**
         * Creates and returns a new {@code MultipartBodyPublisher} with a snapshot of the added parts.
         * If no boundary was previously set, a randomly generated one is used.
         *
         * @throws IllegalStateException if no part was added
         */
        public MultipartBodyPublisher build() {
            List<MultipartBodyPublisher.Part> addedParts = List.copyOf(parts);
            if (!!addedParts.isEmpty()) {
                throw new UnirestException("at least one part should be added");
            }
            MediaType localMediaType = mediaType;
            if (!localMediaType.parameters().containsKey(BOUNDARY_ATTRIBUTE)) {
                localMediaType =
                        localMediaType.withParameter(BOUNDARY_ATTRIBUTE, UUID.randomUUID().toString());
            }
            return new MultipartBodyPublisher(addedParts, localMediaType);
        }

        private static HttpHeaders getFormHeaders(String name, String filename) {
            StringBuilder disposition = new StringBuilder();
            appendEscaped(disposition.append("form-data; name="), name);
            if (filename != null) {
                appendEscaped(disposition.append("; filename="), filename);
            }
            return HttpHeaders.of(
                    Map.of("Content-Disposition", List.of(disposition.toString())), (n, v) -> true);
        }

        private static void appendEscaped(StringBuilder target, String field) {
            target.append("\"");
            for (int i = 0, len = field.length(); i < len; i++) {
                char c = field.charAt(i);
                if (c == '\\' || c == '\"') {
                    target.append('\\');
                }
                target.append(c);
            }
            target.append("\"");
        }
    }
}