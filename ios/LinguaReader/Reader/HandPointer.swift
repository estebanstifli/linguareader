import UIKit

/// Paso de la lectura que sigue la mano: una palabra que suena o la pausa antes de la palabra `tokenId`.
struct PointerStep: Equatable {
    let tokenId: String
    let gap: Bool
}

/// Hacia dónde apunta la mano, en offsets del capítulo: la palabra a señalar, la siguiente (para adelantarse) y el paso.
/// Equivale a `PointerPlan` del lector Android.
struct PointerPlan: Equatable {
    let word: NSRange
    let lead: NSRange?
    let step: PointerStep
}

/// Mano decorativa que señala con el índice, desde abajo, la palabra que se lee, como el dedo de quien lee en voz alta.
/// Mismo algoritmo que `FingerPointerOverlay` en Android: un cursor de lectura avanza por la línea a la velocidad
/// medida de la voz, algo por delante, sin pasar del comienzo de la palabra siguiente; la mano lo sigue con un
/// muelle críticamente amortiguado calculado en cada fotograma (`CADisplayLink`), así el movimiento es continuo
/// aunque los eventos de palabra lleguen a saltos. Nunca intercepta toques y está oculta para VoiceOver.
/// Las cajas llegan en coordenadas de esta vista (la página), que hace de contenedor y recorta la mano.
final class HandPointerView: UIView {
    /// Ancho/alto del dibujo y posición de la yema del índice dentro de él (fracciones).
    private static let aspect: CGFloat = 544 / 1100
    private static let tipX: CGFloat = 0.06
    private static let tipY: CGFloat = 0.006
    private static let handHeight: CGFloat = 528

    private let image = UIImageView()
    private let motion = HandMotion(initialSpeed: 0.14)
    private var inputs = HandMotion.Inputs(box: nil, lead: nil, step: nil, reduceMotion: false, gap: 2)
    private var playing = false
    private var link: CADisplayLink?
    private var lastTimestamp: CFTimeInterval = 0
    private var hide: DispatchWorkItem?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        clipsToBounds = true
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        image.image = Self.loadImage()
        image.alpha = 0.92
        image.isHidden = true
        image.contentMode = .scaleAspectFit
        image.frame = CGRect(x: 0, y: 0, width: Self.handHeight * Self.aspect, height: Self.handHeight)
        addSubview(image)
    }

    required init?(coder: NSCoder) { fatalError() }

    /// El dibujo (`Resources/reading_hand.webp`, el mismo que Android); nil si falta en el paquete.
    static func loadImage() -> UIImage? {
        guard let path = Bundle.main.path(forResource: "reading_hand", ofType: "webp") else { return nil }
        return UIImage(contentsOfFile: path)
    }

    /// Nueva palabra, paso o estado. `target` nil mantiene la última caja un momento (p. ej. al pasar página).
    func update(target: CGRect?, lead: CGRect?, step: PointerStep?, playing: Bool, reduceMotion: Bool) {
        self.playing = playing
        hide?.cancel()
        hide = nil
        if let target {
            inputs = HandMotion.Inputs(box: target, lead: lead, step: step, reduceMotion: reduceMotion, gap: 2)
        } else {
            inputs = HandMotion.Inputs(box: inputs.box, lead: nil, step: step, reduceMotion: reduceMotion, gap: 2)
            let item = DispatchWorkItem { [weak self] in self?.clear() }
            hide = item
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: item)
        }
        startLink()
    }

    /// Oculta la mano al instante (mano desactivada o sin reproducción).
    func clear() {
        hide?.cancel()
        hide = nil
        inputs = HandMotion.Inputs(box: nil, lead: nil, step: nil, reduceMotion: inputs.reduceMotion, gap: 2)
        image.isHidden = true
        stopLink()
    }

    private func startLink() {
        if link == nil {
            lastTimestamp = 0
            let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
            l.add(to: .main, forMode: .common)
            link = l
        }
    }

    private func stopLink() {
        link?.invalidate()
        link = nil
    }

    @objc private func tick(_ link: CADisplayLink) {
        let dt = lastTimestamp == 0 ? 0 : min((link.timestamp - lastTimestamp) * 1000, 50)
        lastTimestamp = link.timestamp
        let settled = motion.frame(inputs, dt: CGFloat(dt), playing: playing)
        if motion.ready, let box = inputs.box, box.maxY > 0, box.minY < bounds.height {
            image.isHidden = false
            image.frame.origin = CGPoint(x: motion.x - image.bounds.width * Self.tipX, y: motion.y - image.bounds.height * Self.tipY)
        } else {
            image.isHidden = true
        }
        if settled && !playing { stopLink() }
    }

    override func removeFromSuperview() {
        stopLink()
        super.removeFromSuperview()
    }
}

/// Estado del seguimiento: posición de la yema, velocidad y ritmo de la voz (coordenadas del contenedor).
final class HandMotion {
    struct Inputs {
        var box: CGRect?
        var lead: CGRect?
        var step: PointerStep?
        var reduceMotion: Bool
        /// Separación entre la línea de texto y la yema.
        var gap: CGFloat
    }

    /// Cuánto se adelanta la yema a la voz (ms).
    private static let anticipationMs: CGFloat = 120
    /// Rigidez del seguimiento (rad/s): más alto = más pegado al objetivo. Amortiguamiento crítico, sin rebote.
    private static let omegaX: CGFloat = 20
    private static let omegaY: CGFloat = 15

    private(set) var x = CGFloat.nan
    private(set) var y = CGFloat.nan
    /// Ya tiene posición.
    private(set) var ready = false
    private var vx: CGFloat = 0
    private var vy: CGFloat = 0

    /// Velocidad de lectura estimada, en puntos por milisegundo.
    private var speed: CGFloat
    private var step: PointerStep?
    /// Reloj de reproducción (solo avanza sonando) y momento en que empezó la palabra actual.
    private var clock: CGFloat = 0
    private var wordStart: CGFloat = 0
    private var lastWordTime: CGFloat = 0
    /// Caja de la palabra actual; y la de la anterior mientras la nueva aún no se ha publicado.
    private var wordBox: CGRect?
    private var staleBox: CGRect?
    private var goalX = CGFloat.nan
    private var goalY = CGFloat.nan

    init(initialSpeed: CGFloat) {
        speed = initialSpeed
    }

    /// Avanza un fotograma de `dt` ms. Devuelve true si la mano está quieta en su objetivo.
    func frame(_ input: Inputs, dt: CGFloat, playing: Bool) -> Bool {
        guard let box = input.box else { return true }
        if playing { clock += dt }
        onStep(input.step)
        if box != staleBox {
            if let previous = staleBox { measure(previous: previous, current: box) }
            staleBox = nil
            wordBox = box
            goal(input, box: box)
        }
        if x.isNaN || input.reduceMotion {
            x = goalX; y = goalY; vx = 0; vy = 0
            ready = true
            return true
        }
        // Muelle críticamente amortiguado (Euler semiimplícito): x'' = ω²(objetivo − x) − 2ω·x'.
        let s = dt / 1000
        vx += (Self.omegaX * Self.omegaX * (goalX - x) - 2 * Self.omegaX * vx) * s
        vy += (Self.omegaY * Self.omegaY * (goalY - y) - 2 * Self.omegaY * vy) * s
        x += vx * s
        y += vy * s
        return abs(goalX - x) < 0.5 && abs(goalY - y) < 0.5 && abs(vx) < 5 && abs(vy) < 5
    }

    private func onStep(_ next: PointerStep?) {
        if next == step { return }
        let wasWord = step?.gap == false
        let isWord = next?.gap == false
        // Ritmo solo entre palabras seguidas; en la pausa entre frases no se mide.
        lastWordTime = (wasWord && isWord) ? clock - wordStart : 0
        // Hasta que llegue la caja nueva, el objetivo no se mueve (no volver a la palabra anterior). De la pausa a
        // su primera palabra la caja es la misma.
        if !(step?.gap == true && isWord) { staleBox = wordBox }
        step = next
        wordStart = clock
    }

    /// Ritmo de la voz: distancia entre el comienzo de la palabra anterior y el de la nueva, entre lo que tardó.
    private func measure(previous: CGRect, current: CGRect) {
        let distance = current.minX - previous.minX
        let sameLine = abs(current.maxY - previous.maxY) < current.height * 0.5
        if sameLine, distance > 0, lastWordTime >= 60, lastWordTime <= 1500 {
            speed = speed * 0.7 + distance / lastWordTime * 0.3
        }
    }

    /// Objetivo de la yema según la palabra, la siguiente y el tiempo que lleva sonando.
    private func goal(_ input: Inputs, box: CGRect) {
        let lineY = box.maxY + input.gap
        let startX = box.minX + min(box.width * 0.15, 6)
        if step?.gap != false { goalX = startX; goalY = lineY; return } // pausa: a la frase siguiente
        let travel = speed * (clock - wordStart + Self.anticipationMs)
        let lead = input.lead
        let leadSameLine = lead.map { abs($0.maxY - box.maxY) < box.height * 0.5 && $0.minX > box.minX } ?? false
        if let lead, leadSameLine {
            let limit = lead.minX + min(lead.width * 0.15, 6)
            goalX = startX + soften(travel, span: limit - startX); goalY = lineY
        } else if let lead, startX + travel > box.maxX {
            // La palabra siguiente empieza otra línea: el dedo se adelanta a ella.
            goalX = lead.minX + min(lead.width * 0.15, 6); goalY = lead.maxY + input.gap
        } else {
            goalX = startX + soften(travel, span: box.maxX - startX); goalY = lineY
        }
    }

    /// Recorrido `travel` limitado a `span` sin frenazo: lineal hasta el 60 % y después se acerca al límite cada vez
    /// más despacio, como un dedo que espera a la palabra siguiente si la voz se demora (comas, palabras largas).
    private func soften(_ travel: CGFloat, span: CGFloat) -> CGFloat {
        if span <= 0 { return 0 }
        let knee = span * 0.6
        if travel <= knee { return travel }
        let rest = span - knee
        return knee + rest * (1 - exp(-(travel - knee) / rest))
    }
}
