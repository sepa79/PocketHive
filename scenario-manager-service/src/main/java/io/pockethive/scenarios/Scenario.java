package io.pockethive.scenarios;

import jakarta.validation.constraints.NotBlank;

public class Scenario {
    @NotBlank
    private String id;
    @NotBlank
    private String name;
    private String description;
    private Template template;

    public Scenario() {}

    public Scenario(String id, String name, String description, Template template) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.template = template;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template;
    }

    public static class Template {
        private String image;
        private java.util.List<Bee> bees;

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public java.util.List<Bee> getBees() {
            return bees;
        }

        public void setBees(java.util.List<Bee> bees) {
            this.bees = bees;
        }
    }

    public static class Bee {
        @NotBlank
        private String role;
        @NotBlank
        private String image;
        private Work work;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public Work getWork() {
            return work;
        }

        public void setWork(Work work) {
            this.work = work;
        }
    }

    public static class Work {
        private String in;
        private String out;

        public String getIn() {
            return in;
        }

        public void setIn(String in) {
            this.in = in;
        }

        public String getOut() {
            return out;
        }

        public void setOut(String out) {
            this.out = out;
        }
    }
}
