
# Scenario Builder – Component Tree (React)

- AppShell
  - LeftNav (Assets, Scenarios, Runs)
  - TopBar (Scenario name, Save/Apply/Export)
  - MainContent
    - TimelineCanvas
      - TimeGrid
      - TrackList
        - TrackRow (SwarmTrack)
          - Block(Ramp/Hold/Spike/Pause/Signal/WaitFor)
    - RightInspector
      - InspectorHeader
      - ScenarioForm | TrackForm | BlockForm
      - PreviewPanels
        - ChartTPS
        - ChartConnections
        - ChartSUTSplit
    - BottomDrawer
      - ValidationPanel
      - EventStream (Run)
