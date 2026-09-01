# AkihaLink product design

The Android app is organized around three user intentions instead of four technical data types:

- **Overview** is the daily control surface: connection, current node, traffic path, and current subscription.
- **Nodes** is a focused selection workspace: search, sort, latency test, and switch.
- **Manage** contains lower-frequency configuration: subscriptions, direct apps, DNS mapping, and runtime health.

Subscriptions and direct-app policy are child destinations of Manage. They deliberately have their own back affordance and do not appear in the bottom navigation.

Pages use a single title without eyebrow or descriptive subtitle. Child destinations pair the same concise title treatment with a compact back affordance.

## Design language

The visual system uses a neutral ink surface hierarchy, indigo for the active connection and selection, mint for healthy runtime state, amber for attention, and red only for failures or destructive actions. Containers communicate hierarchy through tone before borders or elevation.

Spacing follows a compact 4/8/12/16/20/24/28/40/56 dp scale. Primary cards use 24–30 dp corners, regular cards use 18 dp, and small controls use 8–12 dp. Typography favors strong, short headings and quiet supporting text instead of repeated labels.

## Motion and interaction

- Main destinations cross-fade and settle in place so the navigation feels stable.
- Manage child destinations enter from the source side and return in the opposite direction.
- Subscription cards expand in place; actions and traffic details come from the selected card rather than a detached menu.
- Connection state changes animate the hero container, progress, content, and affordance as one component.
- Selection, loading, success, attention, disabled, empty, and destructive states all have explicit visual treatment.

Motion uses a shared emphasized easing curve with 150 ms, 280 ms, and 480 ms duration tiers. Functional state changes remain cancellable and never delay the underlying operation.
