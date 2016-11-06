package ch.thp.prot.properties.extractor.api;


public enum PropertyScope {
    /**
     * no information given
     */
    NOT_SPECIFIED,
    /**
     * A property marked with this value are the same for all nodes and environments
     */
    APPLICATION,
    /**
     * A property marked with this value must be set with different values on any new environment but is the same for
     * one or more nodes.
     */
    ENVIRONMENT,
    /**
     * A property marked with this value must be set with different values on any new application node
     */
    NODE;
}
