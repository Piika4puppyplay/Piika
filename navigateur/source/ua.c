/*
 * Identités du Navigateur (0.12) : le bouton dans la barre d'adresse change
 * ce que le site croit voir (User-Agent, navigator.platform et, pour les
 * navigateurs de la famille Chromium, les « Client Hints » assortis).
 *
 * Par défaut : un Chrome ordinaire sous Linux. Le moteur se présente sinon
 * comme « HeadlessChrome », que beaucoup de sites prennent pour un robot
 * (pages de vérification sans fin, captchas).
 */
#include "nav.h"
#include "puppy.h"
#include <stdio.h>
#include <string.h>

#define CHR "141.0.0.0"
#define CHR_COMPLET "141.0.7390.37"

const identite IDENTITES[] = {
    { "Chrome · Linux (par défaut)", "Linux", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" CHR " Safari/537.36", "Linux x86_64", "Linux", "", 0, 1 },
    { "Chrome · Windows 11", "Win·Chrome", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" CHR " Safari/537.36", "Win32", "Windows", "15.0.0", 0, 1 },
    { "Edge · Windows 11", "Win·Edge", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" CHR " Safari/537.36 Edg/141.0.0.0", "Win32", "Windows", "15.0.0", 0, 2 },
    { "Firefox · Windows 11", "Win·Firefox", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0", "Win32", NULL, NULL, 0, 0 },
    { "Chrome · macOS", "Mac·Chrome", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" CHR " Safari/537.36", "MacIntel", "macOS", "15.6.0", 0, 1 },
    { "Safari · macOS", "Mac·Safari", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15", "MacIntel", NULL, NULL, 0, 0 },
    { "Firefox · macOS", "Mac·Firefox", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15.6; rv:143.0) Gecko/20100101 Firefox/143.0", "MacIntel", NULL, NULL, 0, 0 },
    { "Firefox · Linux", "Linux·Firefox", "Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0", "Linux x86_64", NULL, NULL, 0, 0 },
    { "Chrome · ChromeOS", "ChromeOS", "Mozilla/5.0 (X11; CrOS x86_64 16433.45.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" CHR " Safari/537.36", "Linux x86_64", "Chrome OS", "16433.45.0", 0, 1 },
    { "Chrome · Android (téléphone)", "Android", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" CHR " Mobile Safari/537.36", "Linux armv81", "Android", "15.0.0", 1, 1 },
    { "Firefox · Android", "Android·Fx", "Mozilla/5.0 (Android 15; Mobile; rv:143.0) Gecko/143.0 Firefox/143.0", "Linux armv81", NULL, NULL, 1, 0 },
    { "Safari · iPhone (iOS 18)", "iPhone", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1", "iPhone", NULL, NULL, 1, 0 },
    { "Safari · iPad", "iPad", "Mozilla/5.0 (iPad; CPU OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1", "iPad", NULL, NULL, 1, 0 },
    { "Personnalisée…", "Perso", NULL, "", NULL, NULL, 0, 0 },
};
const int NB_IDENTITES = (int)(sizeof IDENTITES / sizeof IDENTITES[0]);

const char *identite_court(int i)
{
    if (i < 0 || i >= NB_IDENTITES) i = 0;
    return IDENTITES[i].court;
}

void identite_json(int i, const char *perso, char *dst, size_t n)
{
    if (i < 0 || i >= NB_IDENTITES) i = 0;
    const identite *d = &IDENTITES[i];
    char ua[600];
    json_echapper(ua, sizeof ua, d->ua ? d->ua : (perso && perso[0] ? perso : IDENTITES[0].ua));
    size_t o = (size_t)snprintf(dst, n, "{\"userAgent\":%s,\"acceptLanguage\":\"fr-FR,fr;q=0.9,en;q=0.6\",\"platform\":\"%s\"",
                                ua, d->plateforme);
    if (d->chromium && d->ch_plateforme) {
        /* Client Hints cohérents (navigator.userAgentData, en-têtes Sec-CH-UA) */
        const char *marque = d->chromium == 2 ? "Microsoft Edge" : "Google Chrome";
        o += (size_t)snprintf(dst + o, n - o,
            ",\"userAgentMetadata\":{\"brands\":[{\"brand\":\"Chromium\",\"version\":\"141\"},{\"brand\":\"%s\",\"version\":\"141\"},"
            "{\"brand\":\"Not?A_Brand\",\"version\":\"8\"}],\"fullVersionList\":[{\"brand\":\"Chromium\",\"version\":\"" CHR_COMPLET "\"},"
            "{\"brand\":\"%s\",\"version\":\"" CHR_COMPLET "\"},{\"brand\":\"Not?A_Brand\",\"version\":\"8.0.0.0\"}],"
            "\"platform\":\"%s\",\"platformVersion\":\"%s\",\"architecture\":\"%s\",\"model\":\"%s\",\"mobile\":%s,\"bitness\":\"64\"}",
            marque, marque, d->ch_plateforme, d->ch_version, d->mobile ? "" : "x86", d->mobile ? "K" : "", d->mobile ? "true" : "false");
    }
    if (o + 2 < n) { dst[o++] = '}'; dst[o] = 0; }
}
