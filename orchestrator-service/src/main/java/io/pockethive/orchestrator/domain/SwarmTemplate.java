package io.pockethive.orchestrator.domain;

import java.util.List;

/**
 * Template describing the swarm-controller image and the bees to launch.
 */
public class SwarmTemplate {
    private String image;
    private List<SwarmPlan.Bee> bees;

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public List<SwarmPlan.Bee> getBees() {
        return bees;
    }

    public void setBees(List<SwarmPlan.Bee> bees) {
        this.bees = bees;
    }
}
