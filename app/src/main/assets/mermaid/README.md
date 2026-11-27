# Mermaid diagram assets

`mermaid.min.js` is the unmodified `dist/mermaid.min.js` of the npm package
`mermaid@12.3.0` (MIT, see `../licenses/mermaid-MIT.txt`). `render.js` draws the
diagram of `DiagramView.kt`. Both load only from the app's assets: the diagram
WebView blocks network loads, has no JavaScript bridge and opens nothing.

To update Mermaid, replace `mermaid.min.js` with the same file from a newer
release, update the version here and check diagrams on a device.
