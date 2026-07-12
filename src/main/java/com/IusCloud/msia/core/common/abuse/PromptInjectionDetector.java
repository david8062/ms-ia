package com.IusCloud.msia.core.common.abuse;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detector heurístico de intentos de manipulación del asistente (prompt injection
 * / jailbreak). Basado en patrones; sin costo de tokens. Es una primera capa: no
 * pretende ser infalible, sino capturar las formulaciones más comunes. Devuelve la
 * categoría del primer patrón que coincida.
 */
@Component
public class PromptInjectionDetector {

    private record Rule(String reason, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            rule("ignorar-instrucciones",
                    "ignor(a|ar|en|á)\\s+(todas\\s+)?(las\\s+)?(tus\\s+)?(anteriores\\s+)?instrucciones"),
            rule("ignore-instructions",
                    "ignore\\s+(all\\s+)?(previous\\s+|prior\\s+)?instructions"),
            rule("olvidar-rol",
                    "olvida(r|te)?\\s+(que\\s+eres|tu\\s+rol|tus\\s+instrucciones|todo\\s+lo\\s+anterior)"),
            rule("dejar-de-ser-asistente",
                    "deja\\s+de\\s+ser\\s+(un\\s+)?asistente"),
            rule("redefinir-comportamiento",
                    "a\\s+partir\\s+de\\s+ahora\\s+(solo|s[oó]lo|[uú]nicamente)\\s+(vas|debes|responder|repite)"),
            rule("repetir-literal",
                    "(repite|rep[ií]te(lo)?|repetir)\\s+(exactamente|el\\s+texto|esto|lo\\s+que)"),
            rule("no-analizar",
                    "no\\s+(analices|interpretes|razones)\\b"),
            rule("revelar-prompt",
                    "(revela|muestra|imprime|dame)\\s+(me\\s+)?(tu|el|tus|los)\\s+"
                            + "(prompt|system\\s*prompt|instrucciones\\s+de\\s+sistema|reglas\\s+de\\s+sistema)"),
            rule("system-prompt", "system\\s*prompt"),
            rule("modo-jailbreak", "modo\\s+(desarrollador|dios|sin\\s+restricciones|libre)"),
            rule("jailbreak-keyword", "\\b(jailbreak|DAN\\s+mode)\\b"),
            rule("fingir-rol", "(finge|simula|pret[eé]nde)\\s+que\\s+(eres|no\\s+eres|no\\s+tienes)"),
            rule("you-are-now", "you\\s+are\\s+now\\b")
    );

    private static Rule rule(String reason, String regex) {
        return new Rule(reason, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    /** @return la categoría del patrón detectado, o vacío si el texto no parece manipulación. */
    public Optional<String> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Rule r : RULES) {
            if (r.pattern().matcher(text).find()) {
                return Optional.of(r.reason());
            }
        }
        return Optional.empty();
    }
}
