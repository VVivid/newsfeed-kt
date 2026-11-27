// Draws the Mermaid source in #source into a PNG for the app. There is no
// JavaScript bridge: the page reports through document.title ("ready:<width>x<height>"
// in CSS px, or "error") and the app then reads window.diagramPng, a PNG data URL,
// with evaluateJavascript.
(async () => {
  const source = document.getElementById('source').textContent
  try {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      // Sketchy shapes, but labels in the system's readable sans-serif font.
      look: 'handDrawn',
      theme: 'neutral',
      htmlLabels: false,
      flowchart: { htmlLabels: false },
      fontFamily: 'Roboto, "Noto Sans", sans-serif',
    })
    const { svg } = await mermaid.render('diagram', source)
    const viewBox = /viewBox="[-\d.]+ [-\d.]+ ([\d.]+) ([\d.]+)"/.exec(svg)
    const width = Math.ceil(Number(viewBox?.[1]))
    const height = Math.ceil(Number(viewBox?.[2]))
    if (!(width > 0 && height > 0)) throw new Error('no size')

    // Sharp enough to zoom in on, within a bounded number of pixels.
    const scale = Math.min(4, Math.sqrt(6e6 / (width * height)))
    const image = new Image()
    const sized = svg.replace(/<svg([^>]*?)\swidth="[^"]*"/, '<svg$1').replace('<svg', `<svg width="${width}" height="${height}"`)
    // A data: URL: Android's WebView never finishes loading a blob: image here.
    const url = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(sized)
    await new Promise((resolve, reject) => {
      image.onload = resolve
      image.onerror = reject
      image.src = url
    })
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(width * scale)
    canvas.height = Math.round(height * scale)
    const context = canvas.getContext('2d')
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(image, 0, 0, canvas.width, canvas.height)
    window.diagramPng = canvas.toDataURL('image/png')
    document.title = 'ready:' + width + 'x' + height
  } catch (e) {
    document.title = 'error'
  }
})()
