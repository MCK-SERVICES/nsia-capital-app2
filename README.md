# NSIA Capital — application Android

Application Android native (Kotlin) pour consulter son compte NSIA Capital Online
(https://clients.nsiafinance.com) dans de vrais écrans natifs.

- Onglet **Espace client** : le vrai site, tu te connectes toi-même (identifiant, mot de passe, OTP).
  L'application ne lit et n'enregistre jamais tes identifiants.
- Onglet **Tableau de bord** : les tableaux lus sur le site (portefeuille, soldes, ordres, historique…)
  affichés en cartes, avec une synthèse des montants clés.
- **Tout synchroniser** : parcourt automatiquement les pages de consultation de l'espace client
  (jamais les pages d'ordre, de virement, de retrait ou de déconnexion).
- **Exporter** : partage un fichier JSON des données lues (utile pour améliorer l'affichage).
- Onglet **BRVM** : cours du jour sur brvm.org.

## Obtenir l'APK
Chaque envoi sur la branche `main` lance GitHub Actions, qui compile l'APK et le publie
dans l'onglet **Releases** du dépôt.
