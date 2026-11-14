// StreamSofa — interactions de la page : langue, télécommande, onglets, copie, galerie, compteur d'étoiles.
(() => {
  const REPO = "Limoniak/streamsofa-tv";
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const store = {
    get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* stockage indisponible */ } },
  };

  if (window.lucide) lucide.createIcons();

  // Français ; l'anglais est le texte d'origine de la page, gardé à la première bascule.
  const FR = {
    "nav.features": "Fonctionnalités", "nav.install": "Installation", "nav.preview": "Aperçu",
    "hero.eyebrow": "Libre et open source",
    "hero.title": "Twitch, pensé pour <em>le canapé</em>.",
    "hero.lead": "Un client Twitch rapide et moderne pour Android TV et Google TV. Chaque écran se pilote à la télécommande, le texte reste lisible à trois mètres et l’app reste fluide sur les box d’entrée de gamme.",
    "hero.install": "Installer sur la TV", "hero.github": "Voir sur GitHub",
    "hero.f1": "Android TV 8.0+", "hero.f2": "Fonctionne sans compte", "hero.f3": "Aucun traceur",
    "hero.caption": "Vraie capture, pilotée uniquement à la télécommande.",
    "remote.kicker": "Télécommande d’abord",
    "remote.title": "Zappez comme <em>à la télé</em>.",
    "remote.lead": "Dans le lecteur live, chaque touche sert à quelque chose. Essayez la télécommande.",
    "remote.ud": "Changer de chaîne", "remote.ud2": "Zappe entre vos suivis en live, ou les lives populaires en mode invité.",
    "remote.l": "Liste des lives", "remote.l2": "Ouvre la liste des chaînes en direct par-dessus la vidéo.",
    "remote.r": "Chat", "remote.r2": "Affiche le chat à côté du flux, puis son bouton « Écrire » quand vous êtes connecté.",
    "remote.ok": "Barre d’actions", "remote.ok2": "Qualité, son seul, mise en veille, multi-view, page de la chaîne, statistiques.",
    "remote.h": "Appui long sur une carte", "remote.h2": "Ouvre la page de la chaîne : rediffusions, clips, planning et panneaux.",
    "feat.kicker": "Fonctionnalités",
    "feat.title": "Tout ce que vous regardez sur Twitch, <em>sur grand écran</em>.",
    "feat.lead": "Un cœur en Rust pour les API Twitch, le chat et le stockage, avec une interface Jetpack Compose for TV.",
    "f.live": "Lives", "f.live2": "Zapping rapide, les chaînes voisines étant préparées pendant que vous regardez, et qualité retenue pour chaque chaîne.",
    "f.live3": "Mode faible latence expérimental : environ 3,7 s de retard sur le streamer au lieu de 7,2 s, sur les chaînes qui le permettent.",
    "f.live4": "Son seul, qui continue en arrière-plan.",
    "f.live5": "Mise en veille (15, 30, 60 ou 90 minutes).",
    "f.live6": "Statistiques du flux : résolution, débit, retard, images perdues.",
    "f.live7": "Aperçu animé après un instant de focus sur une carte.",
    "f.multi": "Multi-view", "f.multi2": "Deux à quatre flux côte à côte, sur les appareils capables de les décoder. Le son suit la tuile sélectionnée, et chaque tuile peut être remplacée.",
    "f.chat": "Chat", "f.chat2": "Lecture et envoi de messages, réponses et sélecteur d’émotes, badges, émotes 7TV / BTTV / FFZ (animées si l’appareil le permet), sondages et prédictions, citations mises en valeur, mots et pseudos masqués.",
    "f.acc": "Comptes", "f.acc2": "Connexion en scannant un QR code avec le téléphone, sans rien taper sur la TV. Plusieurs comptes et un mode invité, chacun avec ses réglages, un PIN en option et un profil enfants.",
    "f.vod": "Rediffusions", "f.vod2": "Reprise là où vous vous étiez arrêté, par profil, avec le chat rejoué en synchro. Et les clips.",
    "f.disc": "Découvrir", "f.disc2": "Un accueil à votre façon : suivis et épinglés, continuer à regarder, regardé récemment, catégories favorites, des lives pour vous, les plus regardés dans votre langue. Rangées affichables, masquables et réordonnables.",
    "f.tv": "Android TV", "f.tv2": "Rangées « Continuer à regarder » et « Chaînes suivies en live », recherche globale et vocale, et les liens twitch.tv s’ouvrent dans l’app.",
    "f.perf": "Léger sur les vieilles box", "f.perf2": "Cœur natif en Rust, Baseline Profile pour un démarrage rapide, effets lourds coupés sur les appareils à faible mémoire.",
    "f.priv": "Respect de la vie privée", "f.priv2": "Jetons chiffrés par le Keystore Android et jamais journalisés. Aucun traceur ; le journal de crash, optionnel, reste sur l’appareil.",
    "f.upd": "Mises à jour intégrées", "f.upd2": "Les nouvelles versions arrivent des GitHub Releases et sont proposées au lancement. Les bêtas sont en option.",
    "f.lang": "Six langues", "f.lang2": "Anglais, français, espagnol, allemand, italien et portugais du Brésil. L’interface suit la langue du système, ou le réglage propre à l’app sur Android 13+.",
    "feat.note": "Les pubs insérées par Twitch passent normalement : StreamSofa ne les bloque jamais et ne cherche pas à obtenir les avantages abonné ou Turbo. Suivre une chaîne se fait sur le téléphone (un QR code sur chaque page de chaîne), car Twitch a fermé cette API aux apps tierces.",
    "prev.kicker": "Aperçu", "prev.title": "Conçu pour un écran à trois mètres.",
    "prev.lead": "Captures de l’émulateur Google TV en 1080p.",
    "prev.home": "<b>Accueil</b> : les suivis d’abord, puis les lives et catégories populaires.",
    "prev.player": "<b>Lecteur</b> : OK ouvre la barre d’actions, ▶ affiche le chat.",
    "prev.list": "<b>Liste des lives</b> : ◀ l’ouvre, ▲ et ▼ changent directement de chaîne.",
    "prev.settings": "<b>Paramètres</b> : chat, qualité, faible latence, couleur d’accent, mises à jour.",
    "inst.kicker": "Démarrage rapide", "inst.title": "Sur votre TV en <em>deux minutes</em>.",
    "inst.lead": "StreamSofa est distribué en APK, hors Play Store. Une fois installé, il se met à jour tout seul.",
    "inst.tabTv": "Depuis la TV (le plus simple)", "inst.tabPc": "Depuis un ordinateur", "inst.tabSrc": "Compiler les sources",
    "tv.s1": "Installez Downloader", "tv.s1b": "Cherchez « Downloader » (par AFTVnews) dans le Play Store de la TV. Elle est gratuite.",
    "tv.s2": "Autorisez-la à installer des apps", "tv.s2b": "Paramètres → Applications → Sécurité et restrictions → Sources inconnues → activez Downloader. Le chemin exact dépend de la TV.",
    "tv.s3": "Tapez cette adresse dans Downloader", "tv.s3b": "Elle télécharge la dernière version, compatible avec tous les appareils. Confirmez ensuite l’installation.",
    "tv.s4": "Ouvrez StreamSofa", "tv.s4b": "Choisissez « Invité » pour regarder tout de suite, ou ajoutez votre compte Twitch en scannant le QR code avec votre téléphone.",
    "pc.s1": "Téléchargez l’APK", "pc.s1b": "Dans la dernière release, prenez le fichier adapté à votre appareil :",
    "pc.a1": "Box et TV récentes (Chromecast avec Google TV, Shield…)", "pc.a2": "Box anciennes ou 32 bits (Mi Box S…)", "pc.a3": "Dans le doute",
    "pc.s2": "Activez le débogage réseau sur la TV", "pc.s2b": "Paramètres → Système → À propos → appuyez 7 fois sur « Build de l’OS Android TV », puis Options pour les développeurs → débogage USB / réseau.",
    "pc.s3": "Installez avec adb", "pc.s3b": "Remplacez l’adresse par l’IP de votre TV (Paramètres → Réseau).",
    "src.s1": "Prérequis", "src.s1b": "JDK 21, SDK Android (plateforme 37), NDK 29.0.14206865, Rust stable avec les cibles Android et cargo-ndk.",
    "src.s2": "Configuration", "src.s2b": "Enregistrez une application de type « Public » sur la console développeur Twitch, puis renseignez le chemin du SDK et <code>twitch.clientId</code>.",
    "src.s3": "Compiler et installer", "src.s3b": "Ajoutez <code>-Plumen.abis=arm64-v8a</code> pour ne compiler qu’une ABI et itérer plus vite.",
    "foot.by": "Créé par <a href=\"https://github.com/Limoniak\">Limoniak</a>. Logiciel libre sous licence <a href=\"https://github.com/Limoniak/streamsofa-tv/blob/main/LICENSE\">GPL-3.0</a>.",
    "foot.disc": "StreamSofa est une app non officielle, sans lien avec Twitch Interactive, Inc., ni approuvée ou sponsorisée par elle. Twitch est une marque de Twitch Interactive, Inc.",
    "foot.rel": "Releases", "foot.issues": "Signaler un bug", "foot.road": "Feuille de route", "foot.log": "Nouveautés", "foot.lic": "Licence",
  };
  const EN = {};
  const nodes = $$("[data-i18n]");
  nodes.forEach((el) => { EN[el.dataset.i18n] = el.innerHTML; });

  const langButton = $("#lang");
  function setLang(lang) {
    const dict = lang === "fr" ? FR : EN;
    nodes.forEach((el) => { const t = dict[el.dataset.i18n]; if (t) el.innerHTML = t; });
    document.documentElement.lang = lang;
    langButton.innerHTML = lang === "fr" ? "EN / <b>FR</b>" : "<b>EN</b> / FR";
    store.set("lang", lang);
  }
  let lang = store.get("lang") || ((navigator.language || "").toLowerCase().startsWith("fr") ? "fr" : "en");
  if (lang === "fr") setLang("fr");
  langButton.addEventListener("click", () => { lang = lang === "fr" ? "en" : "fr"; setLang(lang); });

  // Télécommande : une touche met en valeur sa ligne dans la liste, et inversement.
  const rows = $$(".keymap li");
  const keys = $$("[data-key]").filter((el) => !el.matches(".keymap li"));
  function highlight(key) {
    rows.forEach((row) => row.classList.toggle("on", row.dataset.key.split(" ").includes(key)));
    keys.forEach((k) => k.classList.toggle("on", k.dataset.key === key));
  }
  keys.forEach((k) => {
    k.addEventListener("click", () => highlight(k.dataset.key));
    k.addEventListener("mouseenter", () => highlight(k.dataset.key));
  });
  rows.forEach((row) => row.addEventListener("mouseenter", () => highlight(row.dataset.key.split(" ")[0])));
  highlight("ok");

  // Onglets d'installation (flèches gauche / droite comme sur une liste d'onglets ARIA).
  const tabs = $$(".tab");
  function selectTab(tab) {
    tabs.forEach((t) => {
      const on = t === tab;
      t.setAttribute("aria-selected", on);
      t.tabIndex = on ? 0 : -1;
      $("#" + t.getAttribute("aria-controls")).hidden = !on;
    });
  }
  tabs.forEach((tab, i) => {
    tab.addEventListener("click", () => selectTab(tab));
    tab.addEventListener("keydown", (e) => {
      const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
      if (!step) return;
      const next = tabs[(i + step + tabs.length) % tabs.length];
      selectTab(next);
      next.focus();
    });
  });
  selectTab(tabs[0]);

  // Boutons « Copier ».
  $$(".copy").forEach((button) => {
    button.addEventListener("click", async () => {
      const text = $("pre", button.parentElement).textContent;
      try {
        await navigator.clipboard.writeText(text);
      } catch {
        const area = Object.assign(document.createElement("textarea"), { value: text });
        document.body.append(area);
        area.select();
        document.execCommand("copy");
        area.remove();
      }
      button.classList.add("done");
      button.innerHTML = '<i data-lucide="check" class="icon"></i>';
      if (window.lucide) lucide.createIcons();
      setTimeout(() => {
        button.classList.remove("done");
        button.innerHTML = '<i data-lucide="copy" class="icon"></i>';
        if (window.lucide) lucide.createIcons();
      }, 1600);
    });
  });

  // Galerie : agrandissement dans une boîte de dialogue native.
  const box = $("#lightbox");
  $$(".shot button").forEach((b) => b.addEventListener("click", () => {
    const img = $("img", box);
    img.src = b.dataset.full;
    img.alt = $("img", b).alt;
    box.showModal();
  }));
  box.addEventListener("click", (e) => { if (e.target === box) box.close(); });

  // Vidéo de démo : lecture seulement si visible, et jamais si l'utilisateur réduit les animations.
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const demo = $("#demo");
  if (reduced) {
    demo.controls = true;
  } else {
    new IntersectionObserver(([entry]) => (entry.isIntersecting ? demo.play().catch(() => {}) : demo.pause()), { threshold: 0.3 }).observe(demo);
  }

  // Apparition au défilement.
  const io = new IntersectionObserver((entries) => entries.forEach((e) => {
    if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); }
  }), { rootMargin: "0px 0px -8% 0px" });
  $$(".reveal").forEach((el) => io.observe(el));

  // Étoiles et dernière version (API GitHub publique ; la page reste complète si elle ne répond pas).
  fetch(`https://api.github.com/repos/${REPO}`).then((r) => r.ok ? r.json() : null).then((repo) => {
    if (repo && repo.stargazers_count > 0) $("#stars").textContent = "★ " + repo.stargazers_count;
  }).catch(() => {});
  fetch(`https://api.github.com/repos/${REPO}/releases/latest`).then((r) => r.ok ? r.json() : null).then((release) => {
    if (!release) return;
    $("#tag").textContent = release.tag_name;
    const cmd = $("#adb-cmd");
    cmd.textContent = cmd.textContent.replace("0.3.0", release.tag_name.replace(/^v/, ""));
  }).catch(() => {});
})();
