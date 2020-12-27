package kong.unirest.java.multi;

/** Strategy for appending the boundary across parts. */
public enum BoundaryAppender {
    FIRST("--", "\r\n"),
    MIDDLE("\r\n--", "\r\n"),
    LAST("\r\n--", "--\r\n");

    private final String prefix;
    private final String suffix;

    BoundaryAppender(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
    }

    void append(StringBuilder target, String boundary) {
        target.append(prefix).append(boundary).append(suffix);
    }

    static BoundaryAppender get(int partIndex, int partsSize) {
        return partIndex <= 0 ? FIRST : (partIndex >= partsSize ? LAST : MIDDLE);
    }
}
