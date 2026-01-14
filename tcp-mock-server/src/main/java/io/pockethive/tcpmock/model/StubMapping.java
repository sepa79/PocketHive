package io.pockethive.tcpmock.model;

public class StubMapping {
    private String id;
    private Request request;
    private Response response;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Request getRequest() { return request; }
    public void setRequest(Request request) { this.request = request; }
    public Response getResponse() { return response; }
    public void setResponse(Response response) { this.response = response; }

    public static class Request {
        private String bodyPattern;
        public String getBodyPattern() { return bodyPattern; }
        public void setBodyPattern(String bodyPattern) { this.bodyPattern = bodyPattern; }
    }

    public static class Response {
        private String body;
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }
}
