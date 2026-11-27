// Draws the Mermaid source in #source and reports the result through
// document.title, the only channel back to the app (there is no JavaScript
// bridge): "size:<width>x<height>" in CSS px after drawing, or "error" when Mermaid cannot read it.
(async () => {
  const source = document.getElementById('source').textContent
  try {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      look: 'handDrawn',
      theme: 'neutral',
      htmlLabels: false,
      flowchart: { htmlLabels: false },
      fontFamily: '"Comic Sans MS", "Segoe Print", "Bradley Hand", cursive',
    })
    const { svg } = await mermaid.render('diagram', source)
    const container = document.getElementById('diagram-container')
    container.innerHTML = svg
    const drawn = container.querySelector('svg')
    // Natural size; a diagram wider than the screen scrolls sideways.
    const viewBox = drawn.viewBox.baseVal
    if (viewBox && viewBox.width > 0) {
      drawn.setAttribute('width', Math.ceil(viewBox.width))
      drawn.setAttribute('height', Math.ceil(viewBox.height))
      drawn.style.maxWidth = 'none'
    }
    const box = container.getBoundingClientRect()
    document.title = 'size:' + Math.ceil(box.width) + 'x' + Math.ceil(box.height)
  } catch (e) {
    document.title = 'error'
  }
})()
