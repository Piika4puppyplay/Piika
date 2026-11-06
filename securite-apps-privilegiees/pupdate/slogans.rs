//! slogans.rs — le « dico de slogans qui tourne » de la console de secours.
//!
//! Un petit dictionnaire de punchlines maison que la console de réparation
//! (puprecovery) affiche en bandeau, en rotation. Pur, sans E/S : la console C
//! pioche un index (au hasard ou selon le jour) et demande le texte ici.
#![allow(dead_code)]

/// Les slogans, dans l'ordre. En ajouter = ajouter une ligne.
pub static SLOGANS: &[&str] = &[
    "PuppyOS : même la confiance doit passer les tests.",
    "Le petit chiot infirmier ne distribue pas les medicaments sans verifier les ordonnances.",
    "Aucun medicament ne sera administre sans verification de l'ordonnance. \
Aucun composant ne sera repare sans verification de son integrite.",
    "Le petit chiot infirmier veille sur vos composants privilegies.",
    "PuppyOS ne fait pas confiance a son createur. La console ne fait pas \
confiance a PuppyOS. Et PuppyOS ne fait pas confiance a sa console.",
    "La console ne prescrit jamais a l'aveugle. Elle verifie, elle \
diagnostique, puis elle soigne.",
];

/// Nombre de slogans.
pub fn nombre() -> usize {
    SLOGANS.len()
}

/// Le slogan d'index `i`, en rotation (modulo le nombre). Jamais de panique.
pub fn slogan(i: usize) -> &'static str {
    SLOGANS[i % SLOGANS.len()]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rotation_sans_panique() {
        assert!(nombre() >= 1);
        // on fait plusieurs tours : jamais de débordement, toujours non vide
        for i in 0..(nombre() * 3 + 1) {
            assert!(!slogan(i).is_empty());
        }
        // la rotation revient au début
        assert_eq!(slogan(0), slogan(nombre()));
    }

    #[test]
    fn pas_de_oe_dans_la_police() {
        // la police bitmap de PuppyOS n'a pas « œ » : on l'évite dans les slogans
        for s in SLOGANS {
            assert!(!s.contains('\u{0153}'), "slogan contient oe : {s}");
        }
    }
}
