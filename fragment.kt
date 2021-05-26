/**
replace a fragment
*/

 private fun addProfileFragment() {
        supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ProfileFragment())
                .commitAllowingStateLoss()
    }
