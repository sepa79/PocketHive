
# Component Tree (React)

- ScenarioApp (entry)
  - ShellBridge (auth, toasts, telemetry)
  - LeftNav (Assets, Scenarios, Runs)
  - TopBar (Scenario name, Save / Apply / Export)
  - Main
    - TimelineCanvas
      - TimeAxis
      - TrackList
        - TrackRow
          - Block (Hold|Ramp|Pause)
    - Inspector
      - ScenarioForm
      - TrackForm
      - BlockForm
      - PreviewPane
        - ChartTPS
        - ChartVolume
    - ValidationPanel
