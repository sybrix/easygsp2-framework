package sybrix.easygsp2.util

class ExpiryList extends ArrayList {
        Long timeout

        @Override
        boolean add(Object o) {
                return super.add(new ExpiryItem(o))
        }

        @Override
        Object get(int index) {
                ExpiryItem o = (ExpiryItem) super.get(index)

                if ((System.currentTimeMillis() - o.created) < timeout) {
                        return o.value
                }

                return null
        }

        @Override
        int size() {
                return super.size()
        }
}

class ExpiryItem {
        Object value
        Date created

        ExpiryItem(Object o) {
                this.value = o
                created = new Date()
        }
}
