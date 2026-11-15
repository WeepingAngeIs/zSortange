package dev.anya.storage;

public final class SharedBaseCoreTests {
    public static void main(String[] args){
        int checks=0;
        for(int mask=-32;mask<128;mask++)for(int required=-8;required<64;required++){
            boolean expected=mask>=0&&mask<=63&&required>=0&&required<=63&&(mask&required)==required;
            if(BasePermissions.allows(mask,required)!=expected)throw new AssertionError("Permission mismatch "+mask+" / "+required);
            checks++;
        }
        if(BasePermissions.LEGACY!=7||BasePermissions.ALL!=63)throw new AssertionError("Legacy mask changed");
        System.out.println("PASS: "+(checks+2)+" shared-base permission assertions");
    }
}
